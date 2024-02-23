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
    fun createActor(registerInformation: ActorRegisterInformation<out Any>): ActorReference {
        val actorReference = createActorReference()
        actorsRegisteredMap.putIfAbsent(actorReference, registerInformation)
        return actorReference
    }

    private fun createActorReference() = UUID.randomUUID().toString()

    fun actorExists(actorReference: ActorReference): Boolean = actorsRegisteredMap.containsKey(actorReference)

}

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

suspend fun ActorReference.tell(message: Any) {
    KaktorManager.tell(this, message)
}

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
