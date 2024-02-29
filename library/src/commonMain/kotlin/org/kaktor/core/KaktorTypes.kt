package org.kaktor.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlin.reflect.KClass

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
    val sendChannel: SendChannel<Any>
)

sealed interface RegisterInformation
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
open class ActorRegisterInformation<M : Any>(
    open val actorClass: KClass<out Kaktor<M>>,
    open vararg val actorStartupProperties: Any
) : RegisterInformation {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ActorRegisterInformation<*>) return false

        if (actorClass != other.actorClass) return false
        if (!actorStartupProperties.contentEquals(other.actorStartupProperties)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = actorClass.hashCode()
        result = 31 * result + actorStartupProperties.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "ActorRegisterInformation(actorClass=$actorClass, actorStartupProperties=${actorStartupProperties.contentToString()})"
    }
}

class ShardActorRegisterInformation<M: Any>(
    val shardBy: (M) -> String,
    actorClass: KClass<out Kaktor<M>>,
    vararg actorStartupProperties: Any
) : ActorRegisterInformation<M>(
    actorClass, actorStartupProperties
)