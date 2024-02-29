package org.kaktor.core

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.onSuccess
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.kaktor.core.commands.AskCommand
import org.kaktor.core.commands.PoisonPill
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


internal val actorsRegisteredMap = ConcurrentHashMap<ActorRef, RegisterInformation<out Any>>()
internal val actorsMap = ConcurrentHashMap<String, ActorInformation>()
internal val errorChannel = Channel<Any>(capacity = UNLIMITED)
internal val actorsPassivationJobs = ConcurrentHashMap<String, Job>()

private val DEFAULT_KAKTOR_PASSIVATION: Long? = null

data class KaktorManagerSettings(
    val actorPassivationTime: Long? = DEFAULT_KAKTOR_PASSIVATION,
    val shardedActorsPassivationTime: Long? = DEFAULT_KAKTOR_PASSIVATION,
)

class KaktorManager(private val settings: KaktorManagerSettings = KaktorManagerSettings()) {
    /**
     * Creates an actor with the given [registerInformation] and returns its reference.
     *
     * @param registerInformation The information used to register the actor. It should include the actor class
     * and any startup properties required by the actor.
     * @return The reference of the created actor.
     */
    fun createActor(registerInformation: RegisterInformation<out Any>): ActorRef {
        val actorReference = when (registerInformation) {
            is ShardActorRegisterInformation<out Any> -> ShardReference(this, "${registerInformation.actorClass}")
            else -> ActorReference(this, UUID.randomUUID().toString())
        }
        actorsRegisteredMap.putIfAbsent(actorReference, registerInformation)
        return actorReference
    }

    fun actorExists(actorReference: ActorRef): Boolean = actorsRegisteredMap.containsKey(actorReference)

    internal suspend fun <M : Any> actorInformation(
        actorRegisterInformation: RegisterInformation<out Any>,
        message: M,
        destination: ActorRef
    ): ActorInformation {
        val actor: ActorInformation = when (actorRegisterInformation) {
            is ShardActorRegisterInformation<*> -> {
                try {
                    val actualMessage = if (message is AskCommand) {
                        message.message
                    } else message

                    @Suppress("UNCHECKED_CAST")
                    val shardedDestination =
                        (actorRegisterInformation as ShardActorRegisterInformation<M>).shardBy(actualMessage as M)
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
        actorRegisterInformation: RegisterInformation<T>,
        message: Any,
    ): ActorInformation {
        val actorClass = actorRegisterInformation.actorClass
        val actorStartupProperties = actorRegisterInformation.actorStartupProperties

        val messageChannel = Channel<Any>(capacity = UNLIMITED)

        val actorInstance = try {
            actorClass.callByArguments(actorStartupProperties)?.apply {
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
        setupPassivation(actorMapKey, actorRegisterInformation.passivation, destination)

        return ActorInformation(
            reference = destination,
            handlingJob = job,
            actorInstance = actorInstance,
            sendChannel = messageChannel,
            mappedKey = actorMapKey
        ).also {
            actorsMap[actorMapKey] = it
        }
    }

    internal fun setupPassivation(actorMapKey: String, actorRegisteredPassivation: Long?, actorRef: ActorRef) {
        when (actorRef) {
            is ActorReference -> actorRegisteredPassivation ?: settings.actorPassivationTime
            is ShardReference -> actorRegisteredPassivation ?: settings.shardedActorsPassivationTime
        }?.let {
            actorsPassivationJobs[actorMapKey] = passivationJob(actorMapKey, it)
        }
    }

    private fun passivationJob(actorMapKey: String, ttl: Long): Job = CoroutineScope(Dispatchers.Default).launch {
        val actor = actorsMap[actorMapKey] ?: return@launch
        delay(ttl)
        actor.sendChannel.trySend(PoisonPill)
        while (actor.actorInstance.isStarted) {
            delay(10)
        }
        actorsMap.remove(actorMapKey)
    }
}
