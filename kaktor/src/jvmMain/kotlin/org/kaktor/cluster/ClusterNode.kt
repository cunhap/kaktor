package org.kaktor.cluster

import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.delay
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import kotlinx.serialization.protobuf.ProtoBuf
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
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalSerializationApi::class)
val PROTOBUF =
    ProtoBuf {
        serializersModule =
            SerializersModule {
                polymorphic(ClusterMessage::class) {
                    subclass(Join::class)
                    subclass(JoinAck::class)
                    subclass(Leave::class)
                    subclass(Heartbeat::class)
                    subclass(NewNodeJoined::class)
                    subclass(ConnectionStart::class)
                    subclass(ConnectionAck::class)
                }
            }
    }

interface ClusterListener {
    suspend fun onNodeJoined(node: NodeInfo)

    suspend fun onNodeLeft(nodeId: String)
}

class ClusterNode(
    nodeId: String,
    nodeAddress: String = "localhost",
    port: Int,
    seedNodes: List<Pair<String, Int>>,
    heartbeatInterval: Long = 5000,
    heartbeatTimeout: Long = 15000,
) {
    private val clusterManager =
        ClusterManager(nodeId, nodeAddress, port, seedNodes, heartbeatInterval, heartbeatTimeout)
    private val messageManager = MessageManager(nodeId)

    private companion object {
        private val logger = LoggerFactory.getLogger(ClusterNode::class.java)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val server =
        embeddedServer(Netty, port = port) {
            install(WebSockets) {
                pingPeriod = 5.seconds
                timeout = 15.seconds
                contentConverter = KotlinxWebsocketSerializationConverter(PROTOBUF)
            }

            routing {
                clusterManager.configureRouting(this)
                messageManager.configureRouting(this)
            }
        }

    suspend fun start() {
        messageManager.addMessageHandler { fromNodeId, message ->
            logger.info("Received message from $fromNodeId: $message")
        }

        server.start(wait = false)
        clusterManager.addListener(messageManager)
        clusterManager.start()
        logger.info("Server started")
        while (clusterManager.isRunning.get()) {
            delay(1000)
        }
    }

    suspend fun stop() {
        messageManager.stop()
        clusterManager.stop()
        server.stop(1000, 1000)
    }
}
