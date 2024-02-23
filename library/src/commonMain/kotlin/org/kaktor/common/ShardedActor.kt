package org.kaktor.common

import java.util.UUID

sealed interface ShardingMechanism
data class Sharded(val shardId: UUID) : ShardingMechanism

abstract class ShardedActor<T : Any>: Kaktor<T>() {
}