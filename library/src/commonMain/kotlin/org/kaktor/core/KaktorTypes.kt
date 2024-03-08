package org.kaktor.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlin.reflect.KClass
import kotlin.time.Duration

sealed interface ActorRef {
    val kActorManager: KaktorManager
    val reference: String
}

data class ActorReference(override val kActorManager: KaktorManager, override val reference: String) : ActorRef
data class ShardReference(override val kActorManager: KaktorManager, override val reference: String) : ActorRef

data class ActorNotRegisteredException(val actorReference: ActorRef) :
    Exception("Message for actor with reference $actorReference undelivered. Actor not registered")

data class ShardedReferenceIncompatible(val actorReference: ShardActorRegisterInformation<*>, val shardedMessage: Any) :
    Exception(
        "Message for actor with reference $actorReference undelivered. " +
                "Reference used for message with class ${shardedMessage::class} is incompatible"
    )

internal data class ActorInformation(
    val reference: ActorRef,
    val handlingJob: Job,
    val actorInstance: Kaktor<out Any>,
    val sendChannel: SendChannel<Any>,
    val mappedKey: String,
)

sealed interface RegisterInformation<M: Any>{
    val actorClass: KClass<out Kaktor<M>>
    val passivation: Long?
    val actorInstanceBuilder: () -> Kaktor<M>
}

/**
 * Represents the information needed to register an actor.
 *
 * @param M The type of the message that the actor can handle.
 * @param actorClass The class of the actor.
 * @param actorStartupProperties The startup properties required by the actor. If the actor has multiple constructor
 * arguments with the same type, this will map the first property here to the first argument and so on, even if they have
 * default values.
 *
 */
data class ActorRegisterInformation<M : Any>(
    override val actorClass: KClass<out Kaktor<M>>,
    override val passivation: Long? = null,
    override val actorInstanceBuilder: () -> Kaktor<M>,
) : RegisterInformation<M>

data class ShardActorRegisterInformation<M: Any>(
    override val actorClass: KClass<out Kaktor<M>>,
    val shardBy: (M) -> String,
    override val passivation: Long? = null,
    override val actorInstanceBuilder: () -> Kaktor<M>,
) : RegisterInformation<M>

