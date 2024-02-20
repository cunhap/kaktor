package org.kaktor.common

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.onFailure
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance

typealias ActorReference = String

interface ActorStartupProperties

data class ActorNotRegisteredException(val actorReference: ActorReference) :
    Exception("Message for actor with reference $actorReference undelivered. Actor not registered")

data class ActorRegisterInformation<M : Any>(
    val actorClass: KClass<out Kaktor<M>>,
    val actorStartupProperties: ActorStartupProperties? = null
)

internal data class ActorInformation(
    val reference: ActorReference,
    val handlingJob: Job,
    val actorInstance: Kaktor<out Any>,
    val sendChannel: SendChannel<Any>
)

internal val actorsRegisteredMap = ConcurrentHashMap<ActorReference, ActorRegisterInformation<out Any>>()
internal val actorsMap = ConcurrentHashMap<ActorReference, ActorInformation>()
internal val errorChannel = Channel<Any>(capacity = UNLIMITED)

object KaktorManager {
    fun createActor(registerInformation: ActorRegisterInformation<out Any>): ActorReference {
        val actorReference = UUID.randomUUID().toString()
        actorsRegisteredMap.putIfAbsent(actorReference, registerInformation)
        return actorReference
    }

    fun actorExists(actorReference: ActorReference): Boolean = actorsRegisteredMap.containsKey(actorReference)

}

internal suspend inline fun <reified M : Any> KaktorManager.tell(
    destination: ActorReference,
    message: M,
    sender: ActorReference? = null
): Result<Unit> {
    val actorChannel: SendChannel<Any> = actorsMap[destination]?.sendChannel ?: run {
        val actorRegisterInformation =
            actorsRegisteredMap[destination] ?: throw ActorNotRegisteredException(destination)
        val (actorClass, _) = actorRegisterInformation
        val messageChannel = Channel<Any>(capacity = UNLIMITED)

        val actorInstance = actorClass.createInstance().apply {
            receiveChannel = messageChannel
            reference = destination
        }
        val job = actorInstance.start().getOrThrow()
        actorsMap[destination] = ActorInformation(
            reference = destination,
            handlingJob = job,
            actorInstance = actorInstance,
            sendChannel = messageChannel
        )
        messageChannel
    }

    actorChannel.trySend(message).onFailure {
        Logger.e { "Message $message has failed to deliver to actor $destination" }
    }

    return Result.success(Unit)
}

suspend fun ActorReference.tell(message: Any, sender: ActorReference? = null) {
    KaktorManager.tell(this, message, sender)
}