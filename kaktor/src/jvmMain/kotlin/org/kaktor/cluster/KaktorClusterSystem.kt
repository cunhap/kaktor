@file:OptIn(ExperimentalSerializationApi::class)

package org.kaktor.cluster

import co.touchlab.kermit.Logger
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.kaktor.cluster.messaging.ClusterCommand
import org.kaktor.cluster.messaging.HealthCheckResponse
import org.kaktor.cluster.messaging.Join
import org.kaktor.cluster.messaging.Leave
import org.kaktor.core.KaktorManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

internal const val listenPortDefault = 20829

data class KaktorClusterConfigs(
    val msgPort: Int = listenPortDefault,
    val clusterHosts: List<SocketAddress>,
    val clusterName: String,
    val memberName: String,
)

@Serializable
internal sealed interface Message

@Serializable
internal data class Envelope(
    @ProtoNumber(0)
    val type: KClass<out Message>,
    @ProtoNumber(1)
    val message: Message,
)

@Serializable
internal data class CCMessage(
    @ProtoNumber(0)
    val clusterName: String,
    @ProtoNumber(1)
    val message: ClusterCommand,
) : Message

data class RemoteNode(
    val socket: Socket,
    val sendChannel: ByteWriteChannel,
)

class KaktorClusterSystem(
    val kaktorManager: KaktorManager,
    private val clusterConfigs: KaktorClusterConfigs,
) : CoroutineScope {
    override val coroutineContext: CoroutineContext = SupervisorJob()
    private val selectorManager = SelectorManager(Dispatchers.IO)
    private lateinit var serverSocket: ServerSocket
    private val clusterNodesSockets = ConcurrentHashMap<SocketAddress, RemoteNode>()
    var launcherJob: Job

    init {
        Logger.i { "Iniciating Node" }
        launcherJob =
            launch {
                serverSocket = aSocket(selectorManager).tcp().bind(port = clusterConfigs.msgPort)
                Logger.i { "Created server socket listening on port ${serverSocket.localAddress}" }
                initiateListener()
                clusterConfigs.clusterHosts
                    .map {
                        async {
                            try {
                                val socket = aSocket(selectorManager).tcp().connect(it)
                                RemoteNode(socket, socket.openWriteChannel(autoFlush = true))
                            } catch (exception: Exception) {
                                Logger.w("Couldn't connect to $it")
                                null
                            }
                        }
                    }.awaitAll()
                    .filterNotNull()
                    .associateBy {
                        it.socket.remoteAddress
                    }.apply {
                        clusterNodesSockets.putAll(this)
                    }
                joinCluster()
            }
        Runtime.getRuntime().addShutdownHook(
            object : Thread() {
                override fun run() {
                    serverSocket.close()
                    launcherJob.cancel()
                }
            },
        )
    }

    private suspend fun initiateListener() {
        supervisorScope {
            launch {
                while (true) {
                    Logger.i("Waiting for connection")
                    val socket = serverSocket.accept()
                    val input = socket.openReadChannel()
                    coroutineScope {
                        launch {
                            while (true) {
                                val messageSize = input.readInt()
                                val message = ByteArray(messageSize)
                                input.readFully(message)
                                if (message.isNotEmpty()) {
                                    launch {
                                        val decodedMessage = ProtoBuf.decodeFromByteArray<Envelope>(message)
                                        handleIncomingMessage(decodedMessage)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleIncomingMessage(message: Envelope) {
        when (message.type) {
            CCMessage::class -> {
                val ccMessage = message.message as CCMessage
                when (ccMessage.message) {
                    is Join -> {
                        val joinMessage = ccMessage.message
                        Logger.d("Join message received from ${joinMessage.memberName}")
                    }

                    is HealthCheckResponse -> TODO()
                    is Leave -> TODO()
                }
            }
        }
    }

    private suspend fun joinCluster() {
        val joinMessage = Join(clusterConfigs.clusterName, clusterConfigs.memberName)
        clusterNodesSockets.forEach { (_, remoteNode) ->
            val envelope = Envelope(CCMessage::class, CCMessage(clusterConfigs.clusterName, joinMessage))
            val message = ProtoBuf.encodeToByteArray(envelope)
            val messageSize = message.size
            remoteNode.sendChannel.writeInt(messageSize)
            remoteNode.sendChannel.writeFully(message)
        }
    }
}

suspend fun main() {
    val clusterConfigs =
        KaktorClusterConfigs(
            clusterHosts = listOf(InetSocketAddress("localhost", 2999)),
            clusterName = "test",
            memberName = "test",
        )
    val kaktorClusterSystem = KaktorClusterSystem(KaktorManager(), clusterConfigs)
    kaktorClusterSystem.launcherJob.join()
}
