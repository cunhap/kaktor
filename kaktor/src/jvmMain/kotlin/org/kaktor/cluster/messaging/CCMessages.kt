@file:OptIn(ExperimentalSerializationApi::class)

package org.kaktor.cluster.messaging

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
sealed interface ClusterCommand {
    val clusterName: String
    val memberName: String
}

@Serializable
data class Join(
    @ProtoNumber(1)
    override val clusterName: String,
    @ProtoNumber(2)
    override val memberName: String
) : ClusterCommand

@Serializable
data class Leave(
    @ProtoNumber(1)
    override val clusterName: String,
    @ProtoNumber(2)
    override val memberName: String
) : ClusterCommand

@Serializable
data class HealthCheckResponse(
    @ProtoNumber(1)
    override val clusterName: String,
    @ProtoNumber(2)
    override val memberName: String
) : ClusterCommand

@Serializable
sealed interface CCManagerMessage {

}

data object OkJoin: CCManagerMessage

@Serializable
sealed interface CCGossipMessge {
    val memberName: String
}

@Serializable
data class MemberJoined(
    @ProtoNumber(1)
    override val memberName: String,
): CCGossipMessge

@Serializable
data class MemberLeft(
    @ProtoNumber(1)
    override val memberName: String,
): CCGossipMessge


