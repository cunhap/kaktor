@file:OptIn(ExperimentalSerializationApi::class)

package org.kaktor.cluster

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.routing.Routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import org.kaktor.cluster.messaging.ClusterMessage
import org.kaktor.cluster.messaging.ConnectionAck
import org.kaktor.cluster.messaging.ConnectionStart
import org.kaktor.cluster.messaging.Heartbeat
import org.kaktor.cluster.messaging.Join
import org.kaktor.cluster.messaging.JoinAck
import org.kaktor.cluster.messaging.Leave
import org.kaktor.cluster.messaging.NewNodeJoined
import org.kaktor.cluster.messaging.NodeInfo
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.set
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets

private val logger = LoggerFactory.getLogger(ClusterManager::class.java)

class ClusterManager(
    private val nodeId: String,
    private val address: String,
    private val port: Int,
    private val seedNodes: List<Pair<String, Int>>,
    private val heartbeatInterval: Long = 5000,
    private val heartbeatTimeout: Long = 15000,
) : CoroutineScope {
    override val coroutineContext = Dispatchers.Default
    val isRunning = AtomicBoolean(false)
    private val supervisorJob = SupervisorJob()
    private val myNodeInfo = NodeInfo(nodeId, address, port)
    private val knownNodes = ConcurrentHashMap<String, NodeInfo>()
    private val nodeSessions = ConcurrentHashMap<String, DefaultWebSocketSession?>()
    private val clusterListeners = mutableListOf<ClusterListener>()
    private val listeningJobs = mutableListOf<Job>()
    private val heartbeatJobs = mutableListOf<Job>()

    private val client =
        HttpClient(CIO) {
            install(ClientWebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(PROTOBUF)
            }
        }

    fun addListener(listener: ClusterListener) {
        clusterListeners.add(listener)
    }

    fun configureRouting(routing: Routing) {
        routing.webSocket("/cluster") {
            try {
                initiateReceivingMessage(this)
            } catch (e: Exception) {
                logger.error("Error in cluster connection", e)
            }
        }
    }

    fun start() =
        launch {
            isRunning.set(true)
            joinCluster()
        }

    private suspend fun handleClusterMessage(
        message: ClusterMessage,
        session: DefaultWebSocketSession,
    ) {
        logger.info("Received cluster message: $message")
        when (message) {
            is Join -> {
                val newNode = NodeInfo(message.nodeId, message.address, message.port)
                val joinAck =
                    JoinAck(
                        nodeId = nodeId,
                        knownNodes = knownNodes.values.toList() + myNodeInfo,
                    )
                session.sendSerializedMessage(joinAck)

                broadcastNewNode(newNode)
                knownNodes[newNode.nodeId] = newNode
                nodeSessions[newNode.nodeId] = session
//                notifyListeners { it.onNodeJoined(newNode) }
            }

            is JoinAck -> {
                message.knownNodes.forEach { nodeInfo ->
                    if (nodeInfo.nodeId != nodeId && !knownNodes.containsKey(nodeInfo.nodeId)) {
                        logger.info("Received join ack from node ${nodeInfo.nodeId}")
                        logger.info("Saving node information and starting coroutine for communication")
                        knownNodes[nodeInfo.nodeId] = nodeInfo
                        nodeSessions[message.nodeId] = session
//                        notifyListeners { it.onNodeJoined(nodeInfo) }
                        initiateReceivingMessage(session)
                    }
                }
            }

            is NewNodeJoined -> {
                if (message.node.nodeId != nodeId && !knownNodes.containsKey(message.node.nodeId)) {
                    knownNodes[message.node.nodeId] = message.node
                    nodeSessions[message.node.nodeId] = null
//                    notifyListeners { it.onNodeJoined(message.node) }
                }
            }

            is Leave -> {
                knownNodes.remove(message.nodeId)
                nodeSessions.remove(message.nodeId)
//                notifyListeners { it.onNodeLeft(message.nodeId) }
            }

            is Heartbeat -> {
                knownNodes[message.nodeId]?.lastHeartbeat = System.currentTimeMillis()
            }

            is ConnectionStart -> {
                val newNode = NodeInfo(message.node.nodeId, message.node.nodeId, message.node.port)
                knownNodes[message.node.nodeId] = newNode
                nodeSessions[message.node.nodeId] = session
                session.sendSerializedMessage(ConnectionAck(myNodeInfo))
                initiateReceivingMessage(session)
            }

            is ConnectionAck -> {
                val nodeInfo = message.node
                knownNodes[nodeInfo.nodeId] = nodeInfo
                nodeSessions[nodeInfo.nodeId] = session
                initiateReceivingMessage(session)
            }
        }
    }

    private suspend fun initiateReceivingMessage(session: DefaultWebSocketSession) {
        val listeningJob =
            coroutineScope {
                launch(supervisorJob) {
                    while (true) {
                        for (frame in session.incoming) {
                            if (frame is Frame.Binary) {
                                val receivedMessage = deserializeMessage(frame)
                                handleClusterMessage(receivedMessage, session)
                            }
                        }
                    }
                }
            }
        val heartbeatJob =
            coroutineScope {
                launch(supervisorJob) {
                    while (true) {
                        sendHeartbeats()
                        checkHeartbeats()
                        delay(heartbeatInterval)
                    }
                }
            }
        listeningJobs.add(listeningJob)
        this.heartbeatJobs.add(heartbeatJob)

        listOf(listeningJob, heartbeatJob).joinAll()
    }

    private suspend fun notifyListeners(action: suspend (ClusterListener) -> Unit) {
        clusterListeners.forEach { listener ->
            try {
                action(listener)
            } catch (e: Exception) {
                logger.error("Error notifying listener: ${e.message}", e)
            }
        }
    }

    private suspend fun joinCluster(): Boolean {
        val joinMessage = Join(nodeId, address, port)
        val seedNodesWithoutSelf = seedNodes.toSet() - Pair(address, port)
        if (seedNodesWithoutSelf.isEmpty()) return true
        for ((seedAddress, seedPort) in seedNodesWithoutSelf) {
            try {
                client.webSocket("ws://$seedAddress:$seedPort/cluster") {
                    logger.info("Connecting to Cluster, sending message $joinMessage")
                    sendSerializedMessage(joinMessage)
                    val responseFrame = incoming.receive()
                    if (responseFrame is Frame.Binary) {
                        val response = deserializeMessage(responseFrame)
                        handleClusterMessage(response, this)
                    }
                }
                return true
            } catch (e: Exception) {
                logger.error("Failed to join through seed $seedAddress:$seedPort: ${e.message}", e)
            }
        }

        return false
    }

    private suspend fun broadcastNewNode(newNode: NodeInfo) {
        val newNodeMessage = NewNodeJoined(newNode)

        knownNodes.values.forEach { node ->
            try {
                nodeSessions[node.nodeId]?.sendSerializedMessage(newNodeMessage)
            } catch (e: Exception) {
                logger.error("Failed to notify ${node.nodeId} about new node ${newNode.nodeId}: ${e.message}", e)
            }
        }
    }

    private suspend fun sendHeartbeats() {
        val heartbeat = Heartbeat(nodeId)
        logger.debug("Sending heartbeats to known nodes {}", knownNodes.keys)
        knownNodes.values.forEach { node ->
            try {
                if (nodeSessions[node.nodeId] == null || !nodeSessions[node.nodeId]!!.isActive) {
                    logger.info("No session for node ${node.nodeId}, trying to establish connection")
                    initiateConnection(node)
                }
                nodeSessions[node.nodeId]?.sendSerializedMessage(heartbeat)
            } catch (e: Exception) {
                logger.error("Failed to send heartbeat to ${node.nodeId}: ${e.message}", e)
            }
        }
    }

    private suspend fun initiateConnection(nodeInfo: NodeInfo) {
        client.webSocket("ws://${nodeInfo.address}:${nodeInfo.port}/cluster") {
            val connectionStart = ConnectionStart(myNodeInfo)
            logger.info("Connecting to $nodeInfo to start connection, sending message $connectionStart")
            sendSerializedMessage(connectionStart)
            val response =
                incoming.receive().let {
                    if (it is Frame.Binary) {
                        deserializeMessage(it)
                    } else {
                        throw IllegalStateException("Expected binary frame, got $it")
                    }
                }
            handleClusterMessage(response, this)
        }
    }

    private suspend fun checkHeartbeats() {
        val now = System.currentTimeMillis()
        val deadNodes = mutableListOf<String>()

        knownNodes.values.forEach { node ->
            if (now - node.lastHeartbeat > heartbeatTimeout) {
                deadNodes.add(node.nodeId)
            }
        }

        deadNodes.forEach { nodeId ->
            knownNodes.remove(nodeId)
            nodeSessions.remove(nodeId)
//            notifyListeners { it.onNodeLeft(nodeId) }
        }
    }

    suspend fun stop() {
        listeningJobs.forEach { it.cancel() }
        heartbeatJobs.forEach { it.cancel() }
        isRunning.set(false)
        knownNodes.clear()
        nodeSessions.values.forEach { it?.close() }
        nodeSessions.clear()
    }
}

// Helper function to print byte arrays
private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }

private suspend fun DefaultWebSocketSession.sendSerializedMessage(message: ClusterMessage) {
    val serialized = PROTOBUF.encodeToByteArray(ClusterMessage.serializer(), message)
    logger.debug("Sending serialized message: ${serialized.toHexString()}")
    val frame = Frame.Binary(true, serialized)
    logger.info("Sending message: $message")
    send(frame)
}

private fun deserializeMessage(frame: Frame.Binary): ClusterMessage {
    logger.debug("Received binary frame with data: ${frame.data.toHexString()}")
    return PROTOBUF.decodeFromByteArray(ClusterMessage.serializer(), frame.data)
}
