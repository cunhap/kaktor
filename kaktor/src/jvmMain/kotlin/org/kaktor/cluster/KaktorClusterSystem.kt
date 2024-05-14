@file:OptIn(ExperimentalSerializationApi::class)

package org.kaktor.cluster

import co.touchlab.kermit.Logger
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.SocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import org.kaktor.cluster.messaging.Join
import org.kaktor.core.KaktorManager
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

internal const val msgPortDefault = 20829

data class KaktorClusterConfigs(
    val msgPort: Int = msgPortDefault,
    val clusterHosts: List<SocketAddress>,
    val clusterName: String,
    val memberName: String,
)

@Serializable
internal sealed interface Envelope {
    val clusterName: String
}

@Serializable
internal data class CCEnvelope(
    @ProtoNumber(1)
    val clusterName: String,
    @ProtoNumber(2)
    val messageClass: KClass<Envelope>
)

class KaktorClusterSystem(val kaktorManager: KaktorManager, private val clusterConfigs: KaktorClusterConfigs) :
    CoroutineScope {
    override val coroutineContext: CoroutineContext = SupervisorJob()
    private val selectorManager = SelectorManager(Dispatchers.IO)
    private lateinit var serverSocket: ServerSocket
    private lateinit var clusterNodesSockets: Map<SocketAddress, Socket>
    private lateinit var launcherJob: Job

    init {
        launcherJob = launch {
            serverSocket = aSocket(selectorManager).tcp().bind(port = clusterConfigs.msgPort)
            clusterNodesSockets = clusterConfigs.clusterHosts.map {
                async {
                    try {
                        aSocket(selectorManager).tcp().connect(it)
                    } catch (exception: Exception) {
                        Logger.w("Couldn't connect to $it")
                        null
                    }
                }
            }.awaitAll().filterNotNull().associateBy {
                it.remoteAddress
            }
            initiateListener()
            joinCluster()
        }
        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() {
                serverSocket.close()
            }
        })
    }

    private fun initiateListener() {
        TODO("Not yet implemented")
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun joinCluster() {
        val joinMessage = Join(clusterConfigs.clusterName, clusterConfigs.memberName)

    }
}