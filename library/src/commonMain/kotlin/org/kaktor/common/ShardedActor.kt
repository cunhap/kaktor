package org.kaktor.common

import java.util.UUID

sealed interface ShardingMechanism
data object NoSharding: ShardingMechanism
data class Sharded(val shardId: UUID) : ShardingMechanism

class ShardedActor {
}