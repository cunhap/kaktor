//@file:OptIn(ExperimentalSerializationApi::class)
//
//package org.kaktor.cluster
//
//import co.touchlab.kermit.Logger
//import io.ktor.network.selector.*
//import io.ktor.network.sockets.*
//import io.ktor.util.network.*
//import io.ktor.utils.io.*
//import kotlinx.coroutines.*
//import kotlinx.coroutines.channels.ClosedReceiveChannelException
//import kotlinx.serialization.*
//import kotlinx.serialization.protobuf.ProtoBuf
//import kotlinx.serialization.protobuf.ProtoNumber
//import kotlinx.uuid.UUID
//import org.kaktor.cluster.messaging.*
//import org.kaktor.core.KaktorManager
//import java.util.concurrent.ConcurrentHashMap
//import kotlin.coroutines.CoroutineContext
//
//internal const val listenPortDefault = 20829
//
//data class KaktorClusterConfigs(
//    val listenPort: Int = listenPortDefault,
//    val clusterHosts: List<SocketAddress>,
//    val clusterName: String,
//    val memberName: String,
//)
//
//@Serializable
//internal sealed interface Message
//
//@Serializable
//internal data class Envelope(
//    @ProtoNumber(0)
//    val message: Message,
//)
//
//@Serializable
//internal data class CCMessage(
//    @ProtoNumber(0)
//    val clusterName: String,
//    @ProtoNumber(1)
//    val message: ClusterCommand,
//) : Message
//
//data class RemoteNode(
//    val socket: Socket,
//    val sendChannel: ByteWriteChannel,
//)
//
//class KaktorClusterSystem(
//    val kaktorManager: KaktorManager,
//    private val clusterConfigs: KaktorClusterConfigs,
//) : CoroutineScope {
//    override val coroutineContext: CoroutineContext = SupervisorJob()
//    private val selectorManager = SelectorManager(Dispatchers.IO)
//    private lateinit var serverSocket: ServerSocket
//    private val clusterNodesSockets = ConcurrentHashMap<SocketAddress, RemoteNode>()
//    var launcherJob: Job
//    private var listening: Boolean = false
//
//    init {
//        Logger.i { "Initiating Node" }
//        launcherJob =
//            launch {
//                serverSocket = aSocket(selectorManager).tcp().bind(port = clusterConfigs.listenPort)
//                Logger.i { "Created server socket listening on port ${serverSocket.localAddress}" }
//                launch {
//                    initiateCCListener()
//                }
//                while (!listening) {
//                    delay(100)
//                }
//                clusterConfigs.clusterHosts
//                    .map {
//                        async {
//                            try {
//                                if (it.toJavaAddress().port == serverSocket.localAddress.toJavaAddress().port) {
//                                    return@async null
//                                }
//                                Logger.i("Connecting to $it")
//                                val socket = aSocket(selectorManager).tcp().connect(it)
//                                RemoteNode(socket, socket.openWriteChannel(autoFlush = true))
//                            } catch (exception: Exception) {
//                                Logger.w("Couldn't connect to $it", exception)
//                                null
//                            }
//                        }
//                    }.awaitAll()
//                    .filterNotNull()
//                    .associateBy {
//                        it.socket.remoteAddress
//                    }.apply {
//                        clusterNodesSockets.putAll(this)
//                    }
//                sendMessage {
//                    Join(clusterConfigs.memberName, UUID().toString())
//                }
//            }
//        Runtime.getRuntime().addShutdownHook(
//            object : Thread() {
//                override fun run() {
//                    serverSocket.close()
//                    launcherJob.cancel()
//                }
//            },
//        )
//    }
//
//    private suspend fun initiateCCListener() {
//        supervisorScope {
//            launch {
//                while (isActive) {
//                    Logger.i("Waiting for connection")
//                    if (!listening) listening = true
//                    val socket = serverSocket.accept()
//                    val input = socket.openReadChannel()
//                    coroutineScope {
//                        launch {
//                            try {
//                                while (isActive) {
//                                    val messageSize = input.readInt()
//                                    val message = ByteArray(messageSize)
//                                    input.readFully(message)
//                                    if (message.isNotEmpty()) {
//                                        launch {
//                                            val decodedMessage = ProtoBuf.decodeFromByteArray<Envelope>(message)
//                                            handleIncomingMessage(decodedMessage)?.let {
//                                                sendToNode(socket.remoteAddress, it)
//                                            }
//                                        }
//                                    }
//                                }
//                            } catch (e: ClosedReceiveChannelException) {
//                                Logger.w("Connection closed unexpectedly", e)
//                            } catch (e: Exception) {
//                                Logger.e("Error while reading from socket", e)
//                            } finally {
//                                socket.close()
//                            }
//                        }
//                    }
//                }
//            }
//        }
//    }
//
//    private fun handleIncomingMessage(message: Envelope): Envelope? {
//        when (message.message) {
//            is CCMessage -> {
//                val ccMessage = message.message
//                when (ccMessage.message) {
//                    is Join -> {
//                        val joinMessage = ccMessage.message
//                        Logger.d("Join message received message: $joinMessage")
//                        val success =
//                            Success(memberName = clusterConfigs.memberName, responseTo = joinMessage.messageId)
//                        return Envelope(
//                            CCMessage(
//                                clusterName = clusterConfigs.clusterName,
//                                message = success
//                            )
//                        )
//                    }
//
//                    is HealthCheckResponse -> TODO()
//                    is Leave -> TODO()
//                    is Success -> {
//                        val successMessage = ccMessage.message
//                        Logger.d("Success message received message: $successMessage")
//                    }
//                }
//            }
//        }
//
//        return null
//    }
//
//    private suspend fun sendMessage(clusterCommandMessage: () -> ClusterCommand) {
//        val joinMessage = clusterCommandMessage()
//        clusterNodesSockets.forEach { (_, remoteNode) ->
//            val envelope = Envelope(CCMessage(clusterConfigs.clusterName, joinMessage))
//            sendToNode(remoteNode.socket.remoteAddress, envelope)
//        }
//    }
//
//    private suspend fun sendToNode(node: SocketAddress, message: Envelope) {
//        val remoteNode = clusterNodesSockets[node] ?: return
//        val messageBytes = ProtoBuf.encodeToByteArray(message)
//        val messageSize = messageBytes.size
//        remoteNode.sendChannel.writeInt(messageSize)
//        remoteNode.sendChannel.writeFully(messageBytes)
//        val responseChannel = remoteNode.socket.openReadChannel()
//        val responseSize = responseChannel.readInt()
//        val responseMessage = ByteArray(responseSize)
//        responseChannel.readFully(responseMessage)
//        if (responseMessage.isNotEmpty()) {
//            val decodedMessage = ProtoBuf.decodeFromByteArray<Envelope>(responseMessage)
//            handleIncomingMessage(decodedMessage)?.let {
//                sendToNode(node, it)
//            }
//
//        }
//    }
//}
//
//suspend fun main() {
//    val hosts = System.getenv("CLUSTER_HOSTS")?.split(",")?.map {
//        val (host, port) = it.split(":")
//        InetSocketAddress(host, port.toInt())
//    } ?: listOf(InetSocketAddress("localhost", listenPortDefault))
//
//    val listenPort = System.getenv("LISTEN_PORT")?.toInt() ?: listenPortDefault
//
//    val clusterConfigs =
//        KaktorClusterConfigs(
//            listenPort = listenPort,
//            clusterHosts = hosts,
//            clusterName = "test",
//            memberName = "test",
//        )
//    val kaktorClusterSystem = KaktorClusterSystem(KaktorManager(), clusterConfigs)
//    kaktorClusterSystem.launcherJob.join()
//}
