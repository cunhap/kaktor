package org.kaktor.common

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.kaktor.common.commands.AskCommand
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

typealias ActorReference = String

data class ActorNotRegisteredException(val actorReference: ActorReference) :
    Exception("Message for actor with reference $actorReference undelivered. Actor not registered")

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

suspend fun ActorReference.ask(message: Any, timeout: Long = 1000): Any? {
    val answerChannel = Channel<Any>()
    val askMessage = AskCommand(message, answerChannel)

    Logger.d { "Sending message $message to actor $this with timeout $timeout and answerChannel $answerChannel" }
    Logger.d { "Answer channel instance: ${answerChannel.printInstanceId()}" }
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

fun Channel<Any>.printInstanceId() =
    "Answer channel instance: ${this::class.java.name}@${Integer.toHexString(System.identityHashCode(this))}"

fun SendChannel<Any>.printInstanceId() =
    "Answer channel instance: ${this::class.java.name}@${Integer.toHexString(System.identityHashCode(this))}"

fun ReceiveChannel<Any>.printInstanceId() =
    "Answer channel instance: ${this::class.java.name}@${Integer.toHexString(System.identityHashCode(this))}"
