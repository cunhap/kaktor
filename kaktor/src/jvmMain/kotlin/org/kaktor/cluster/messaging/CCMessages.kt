@file:OptIn(ExperimentalSerializationApi::class)

package org.kaktor.cluster.messaging

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
sealed interface ClusterMessage

@Serializable
@SerialName("Join")
data class Join(
    @ProtoNumber(1)
    val nodeId: String,
    @ProtoNumber(2)
    val address: String,
    @ProtoNumber(3)
    val port: Int,
) : ClusterMessage

@Serializable
@SerialName("JoinAck")
data class JoinAck(
    @ProtoNumber(1)
    val nodeId: String,
    @ProtoNumber(2)
    val knownNodes: List<NodeInfo>,
) : ClusterMessage

@Serializable
@SerialName("Leave")
data class Leave(
    @ProtoNumber(1)
    val nodeId: String,
) : ClusterMessage

@Serializable
@SerialName("Heartbeat")
data class Heartbeat(
    @ProtoNumber(1)
    val nodeId: String,
) : ClusterMessage

@Serializable
@SerialName("NewNodeJoined")
data class NewNodeJoined(
    @ProtoNumber(1)
    val node: NodeInfo,
) : ClusterMessage

@Serializable
data class ConnectionStart(
    @ProtoNumber(1)
    val node: NodeInfo,
) : ClusterMessage

@Serializable
@SerialName("ConnectionAck")
data class ConnectionAck(
    @ProtoNumber(1)
    val node: NodeInfo,
) : ClusterMessage

@Serializable
@SerialName("NodeInfo")
data class NodeInfo(
    @ProtoNumber(1)
    val nodeId: String,
    @ProtoNumber(2)
    val address: String,
    @ProtoNumber(3)
    val port: Int,
    @ProtoNumber(4)
    var lastHeartbeat: Long = System.currentTimeMillis(),
)
