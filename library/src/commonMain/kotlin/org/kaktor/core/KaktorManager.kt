package org.kaktor.core

import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.kaktor.core.commands.AskCommand
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal val actorsRegisteredMap = ConcurrentHashMap<ActorRef, ActorRegisterInformation<out Any>>()
internal val actorsMap = ConcurrentHashMap<String, ActorInformation>()
internal val errorChannel = Channel<Any>(capacity = UNLIMITED)

open class KaktorManager {
    /**
     * Creates an actor with the given [registerInformation] and returns its reference.
     *
     * @param registerInformation The information used to register the actor. It should include the actor class
     * and any startup properties required by the actor.
     * @return The reference of the created actor.
     */
    fun createActor(registerInformation: ActorRegisterInformation<out Any>): ActorRef {
        val actorReference = when (registerInformation) {
            is ShardActorRegisterInformation<out Any> -> ShardReference(this, "${registerInformation.actorClass}")
            else -> ActorReference(this, UUID.randomUUID().toString())
        }
        actorsRegisteredMap.putIfAbsent(actorReference, registerInformation)
        return actorReference
    }

    fun actorExists(actorReference: ActorRef): Boolean = actorsRegisteredMap.containsKey(actorReference)

    /**
     * Sends a message of type M to the specified actor reference.
     *
     * @param destination The reference of the destination actor.
     * @param message The message to be sent.
     * @return A Result object indicating the success or failure of the operation.
     * @throws ActorNotRegisteredException if the destination actor is not registered.
     */
    internal suspend fun <M : Any> tell(
        destination: ActorRef,
        message: M,
    ): Result<Unit> {
        val actorRegisterInformation =
            actorsRegisteredMap[destination] ?: throw ActorNotRegisteredException(destination)

        val actor: ActorInformation = actorInformation(actorRegisterInformation, message, destination)

        actor.sendChannel.trySend(message).onFailure {
            Logger.e { "Message $message has failed to deliver to actor $destination" }
        }

        return Result.success(Unit)
    }

    private suspend fun <M : Any> actorInformation(
        actorRegisterInformation: ActorRegisterInformation<out Any>,
        message: M,
        destination: ActorRef
    ): ActorInformation {
        val actor: ActorInformation = when (actorRegisterInformation) {
            is ShardActorRegisterInformation<*> -> {
                try {
                    val actualMessage = if(message is AskCommand) {
                        message.message
                    } else message

                    @Suppress("UNCHECKED_CAST")
                    val shardedDestination = (actorRegisterInformation as ShardActorRegisterInformation<M>).shardBy(actualMessage as M)
                    actorsMap[shardedDestination]
                } catch (e: Exception) {
                    Logger.e(e) { "Could not create instance of actor" }
                    throw ShardedReferenceIncompatible(actorRegisterInformation, message)
                }
            }
            else -> actorsMap[destination.reference]
        } ?: createActorInformation(destination, actorRegisterInformation, message)
        return actor
    }

    private suspend fun <T : Any> createActorInformation(
        destination: ActorRef,
        actorRegisterInformation: ActorRegisterInformation<T>,
        message: Any,
    ): ActorInformation {
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

        @Suppress("UNCHECKED_CAST")
        val actorMapKey = when (destination) {
            is ActorReference -> destination.reference
            is ShardReference -> (actorRegisterInformation as ShardActorRegisterInformation<T>).shardBy((message as T))
        }

        val job = actorInstance.start().getOrThrow()
        return ActorInformation(
            reference = destination,
            handlingJob = job,
            actorInstance = actorInstance,
            sendChannel = messageChannel
        ).also {
            actorsMap[actorMapKey] = it
        }
    }
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
