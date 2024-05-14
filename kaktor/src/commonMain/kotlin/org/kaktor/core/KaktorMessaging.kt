package org.kaktor.core

import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.onSuccess
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.kaktor.core.commands.AskCommand

/**
 * Sends a message of type M to the specified actor reference.
 *
 * @param destination The reference of the destination actor.
 * @param message The message to be sent.
 * @return A Result object indicating the success or failure of the operation.
 * @throws ActorNotRegisteredException if the destination actor is not registered.
 */
internal suspend fun <M : Any> KaktorManager.tell(
    destination: ActorRef,
    message: M,
): Result<Unit> {
    val actorRegisterInformation =
        actorsRegisteredMap[destination] ?: throw ActorNotRegisteredException(destination)

    val actor: ActorInformation = actorInformation(actorRegisterInformation, message, destination)

    actor.sendChannel.trySend(message).onFailure {
        Logger.e { "Message $message has failed to deliver to actor $destination" }
    }.onSuccess {
        actorsPassivationJobs[actor.mappedKey]?.cancel()
        setupPassivation(actor.mappedKey, actorRegisterInformation.passivation, destination)
    }

    return Result.success(Unit)
}

/**
 * Sends a message to the specified actor reference.
 *
 * @param message The message to be sent.
 */
suspend fun ActorRef.tell(message: Any) {
    kActorManager.tell(this, message)
}

/**
 * Sends a message to the specified actor reference and waits for a response.
 *
 * @param message The message to be sent.
 * @param timeout The timeout period for waiting for a response. Default is 10,000 milliseconds.
 * @return The response received from the actor, or null if a response was not received within the timeout period.
 */
suspend fun ActorRef.ask(message: Any, timeout: Long = 10000): Any? {
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