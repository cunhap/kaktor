package org.kaktor.common

import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.kaktor.common.commands.AskCommand
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal val actorsRegisteredMap = ConcurrentHashMap<ActorReference, ActorRegisterInformation<out Any>>()
internal val actorsMap = ConcurrentHashMap<ActorReference, ActorInformation>()
internal val errorChannel = Channel<Any>(capacity = UNLIMITED)

object KaktorManager {
    /**
     * Creates an actor with the given [registerInformation] and returns its reference.
     *
     * @param registerInformation The information used to register the actor. It should include the actor class
     * and any startup properties required by the actor.
     * @return The reference of the created actor.
     */
    fun createActor(registerInformation: ActorRegisterInformation<out Any>): ActorReference {
        val actorReference = createActorReference()
        actorsRegisteredMap.putIfAbsent(actorReference, registerInformation)
        return actorReference
    }

    private fun createActorReference() = UUID.randomUUID().toString()

    fun actorExists(actorReference: ActorReference): Boolean = actorsRegisteredMap.containsKey(actorReference)

}

/**
 * Sends a message of type M to the specified actor reference.
 *
 * @param destination The reference of the destination actor.
 * @param message The message to be sent.
 * @return A Result object indicating the success or failure of the operation.
 * @throws ActorNotRegisteredException if the destination actor is not registered.
 */
internal suspend inline fun <reified M : Any> KaktorManager.tell(
    destination: ActorReference,
    message: M,
): Result<Unit> {
    val actorChannel: SendChannel<Any> = actorsMap[destination]?.sendChannel ?: run {
        val actorRegisterInformation =
            actorsRegisteredMap[destination] ?: throw ActorNotRegisteredException(destination)
        val actorClass = actorRegisterInformation.actorClass
        val actorStartupProperties = actorRegisterInformation.actorStartupProperties

        val messageChannel = Channel<Any>(capacity = UNLIMITED)

        val actorInstance = try {
            actorClass.callByArguments(*actorStartupProperties)?.apply {
                receiveChannel = messageChannel
                reference = destination
            } ?: throw IllegalArgumentException()
        } catch (exception: Exception) {
            Logger.e(exception) {
                "Could not create instance of actor"
            }
            throw exception
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

/**
 * Sends a message to the specified actor reference.
 *
 * @param message The message to be sent.
 */
suspend fun ActorReference.tell(message: Any) {
    KaktorManager.tell(this, message)
}

/**
 * Sends a message to the specified actor reference and waits for a response.
 *
 * @param message The message to be sent.
 * @param timeout The timeout period for waiting for a response. Default is 10,000 milliseconds.
 * @return The response received from the actor, or null if a response was not received within the timeout period.
 */
suspend fun ActorReference.ask(message: Any, timeout: Long = 10000): Any? {
    val answerChannel = Channel<Any>()
    val askMessage = AskCommand(message, answerChannel)

    Logger.d { "Sending message $message to actor $this with timeout $timeout and answerChannel $answerChannel" }
    this.tell(askMessage)

    return coroutineScope {
        try {
            withTimeout(timeout) {
                answerChannel.receiveCatching().getOrNull().also {
                    answerChannel.close()
                }
            }
        } catch (exception: Exception) {
            Logger.e { "Failed to ask message $message with timeout $timeout and answerChannel $answerChannel" }
            null
        }
    }
}
