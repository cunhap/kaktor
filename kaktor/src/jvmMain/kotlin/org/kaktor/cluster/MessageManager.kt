package org.kaktor.cluster

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.routing.Routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import org.kaktor.cluster.messaging.NodeInfo
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.forEach
import kotlin.collections.mutableListOf
import kotlin.collections.set

class MessageManager(
    private val nodeId: String,
) : ClusterListener {
    private val messageChannels = ConcurrentHashMap<String, Channel<String>>()
    private val messageHandlers = mutableListOf<(String, String) -> Unit>()

    companion object {
        private val logger = LoggerFactory.getLogger(MessageManager::class.java)
    }

    fun configureRouting(routing: Routing) {
        routing.webSocket("/messages") {
            try {
                handleMessageConnection(this)
            } catch (e: Exception) {
                logger.error("Error in message connection: ${e.message}", e)
            }
        }
    }

    private suspend fun handleMessageConnection(session: DefaultWebSocketSession) {
        var remoteNodeId: String? = null

        for (frame in session.incoming) {
            logger.info("Received message: $frame")
            when (frame) {
                is Frame.Text -> {
                    val text = frame.readText()
                    logger.info("Received text: $text")
                    if (remoteNodeId == null) {
                        remoteNodeId = text
                        val channel = Channel<String>()
                        messageChannels[remoteNodeId] = channel

                        CoroutineScope(Dispatchers.IO).launch {
                            for (message in channel) {
                                logger.info("Sending message: $message")
                                session.send(Frame.Text(message))
                            }
                        }
                    } else {
                        handleMessage(remoteNodeId, text)
                    }
                }

                else -> {} // Ignore other frame types
            }
        }

        remoteNodeId?.let {
            messageChannels.remove(it)
        }
    }

    private fun handleMessage(
        fromNodeId: String,
        message: String,
    ) {
        messageHandlers.forEach { handler ->
            handler(fromNodeId, message)
        }
    }

    fun addMessageHandler(handler: (String, String) -> Unit) {
        messageHandlers.add(handler)
    }

    suspend fun sendMessage(
        toNodeId: String,
        message: String,
    ) {
        messageChannels[toNodeId]?.send(message)
    }

    override suspend fun onNodeJoined(node: NodeInfo) {
        connectMessageChannel(node.nodeId, node.address, node.port)
    }

    override suspend fun onNodeLeft(nodeId: String) {
        messageChannels.remove(nodeId)?.close()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun connectMessageChannel(
        remoteId: String,
        address: String,
        port: Int,
    ) {
        if (!messageChannels.containsKey(remoteId)) {
            try {
                val client =
                    HttpClient(CIO) {
                        install(io.ktor.client.plugins.websocket.WebSockets) {
                            contentConverter = KotlinxWebsocketSerializationConverter(PROTOBUF)
                        }
                    }

                client.webSocket("ws://$address:$port/messages") {
                    logger.info("Connecting to message channel of $remoteId")
                    logger.info("Sending nodeId: $nodeId")
                    send(Frame.Text(nodeId))

                    val channel = Channel<String>()
                    messageChannels[remoteId] = channel

                    CoroutineScope(Dispatchers.IO).launch {
                        for (message in channel) {
                            send(Frame.Text(message))
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to establish message connection with $remoteId: ${e.message}", e)
            }
        }
    }

    fun stop() {
        messageChannels.values.forEach { it.close() }
        messageChannels.clear()
    }
}
