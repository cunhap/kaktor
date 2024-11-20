@file:OptIn(ExperimentalSerializationApi::class)

package org.kaktor.cluster

import io.ktor.network.sockets.SocketAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.system.exitProcess

internal const val LISTEN_PORT_DEFAULT = 20829
internal const val NODE_ID_DEFAULT = "node-1"

data class KaktorClusterConfigs(
    val listenPort: Int = LISTEN_PORT_DEFAULT,
    val clusterHosts: List<SocketAddress>,
    val clusterName: String,
    val memberName: String,
)

suspend fun main() {
    val hosts =
        System.getenv("CLUSTER_HOSTS")?.split(",")?.map {
            it.split(":").let { (host, port) -> Pair(host, port.toInt()) }
        } ?: listOf()

    val listenPort = System.getenv("LISTEN_PORT")?.toInt() ?: LISTEN_PORT_DEFAULT
    val nodeId = System.getenv("NODE_ID") ?: NODE_ID_DEFAULT

    val kAktorClusterSystem =
        ClusterNode(
            nodeId = nodeId,
            port = listenPort,
            seedNodes = hosts,
        )
    kAktorClusterSystem.start()
// Register shutdown hook to stop the node gracefully
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking {
                kAktorClusterSystem.stop()
            }
            exitProcess(0)
        },
    )

    // Wait for the application to be stopped
    runBlocking {
        while (true) {
            delay(1000)
        }
    }
}
