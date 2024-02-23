package org.kaktor.common

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlin.reflect.KClass

typealias ActorReference = String

data class ActorNotRegisteredException(val actorReference: ActorReference) :
    Exception("Message for actor with reference $actorReference undelivered. Actor not registered")


internal data class ActorInformation(
    val reference: ActorReference,
    val handlingJob: Job,
    val actorInstance: Kaktor<out Any>,
    val sendChannel: SendChannel<Any>
)

class ActorRegisterInformation<M : Any>(
    val actorClass: KClass<out Kaktor<M>>,
    vararg val actorStartupProperties: Any
) {

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
