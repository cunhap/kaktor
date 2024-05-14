package org.kaktor.core

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.uuid.UUID
import org.kaktor.core.commands.AskCommand
import org.kaktor.core.commands.PoisonPill

internal expect val actorsRegisteredMap: MutableMap<ActorRef, RegisterInformation<out Any>>
internal expect val actorsMap: MutableMap<String, ActorInformation>
internal val errorChannel = Channel<Any>(capacity = UNLIMITED)
internal expect val actorsPassivationJobs: MutableMap<String, Job>

private val DEFAULT_KAKTOR_PASSIVATION: Long? = null

data class KaktorManagerSettings(
    val actorPassivationTime: Long? = DEFAULT_KAKTOR_PASSIVATION,
    val shardedActorsPassivationTime: Long? = DEFAULT_KAKTOR_PASSIVATION,
)

class KaktorManager(
    private val settings: KaktorManagerSettings = KaktorManagerSettings(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    /**
     * Creates an actor with the given [registerInformation] and returns its reference.
     *
     * @param registerInformation The information used to register the actor. It should include the actor class
     * and any startup properties required by the actor.
     * @return The reference of the created actor.
     */
    fun createActor(registerInformation: RegisterInformation<out Any>): ActorRef {
        val actorReference = when (registerInformation) {
            is ShardActorRegisterInformation<out Any> -> ShardReference(
                this,
                "${registerInformation.actorClass.simpleName}"
            )

            else -> ActorReference(this, UUID().toString())
        }
        if (!actorsRegisteredMap.containsKey(actorReference)) actorsRegisteredMap[actorReference] = registerInformation
        return actorReference
    }

    fun actorExists(actorReference: ActorRef): Boolean = actorsRegisteredMap.containsKey(actorReference)

    internal suspend fun <M : Any> actorInformation(
        actorRegisterInformation: RegisterInformation<out Any>,
        message: M,
        destination: ActorRef
    ): ActorInformation {
        val actor: ActorInformation = when (destination) {
            is ShardReference -> {
                @Suppress("UNCHECKED_CAST")
                val shardActorReference = actorRegisterInformation as ShardActorRegisterInformation<M>
                try {
                    val actualMessage = if (message is AskCommand) {
                        @Suppress("UNCHECKED_CAST")
                        message.message as M
                    } else message
                    val shardedDestination = getShardKey(destination, actualMessage, shardActorReference)
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
        val instanceBuilder = actorRegisterInformation.actorInstanceBuilder

        val messageChannel = Channel<Any>(capacity = UNLIMITED)

        val actorInstance = try {
            instanceBuilder().apply {
                receiveChannel = messageChannel
                reference = destination
            }
        } catch (exception: Exception) {
            Logger.e(exception) {
                "Could not create instance of actor"
            }
            throw exception
        }

        @Suppress("UNCHECKED_CAST")
        val actorMapKey = when (destination) {
            is ActorReference -> destination.reference
            is ShardReference -> getShardKey(
                destination,
                message as T,
                actorRegisterInformation as ShardActorRegisterInformation
            )
        }

        val job = actorInstance.start().getOrThrow()

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

    private fun passivationJob(actorMapKey: String, ttl: Long): Job = CoroutineScope(dispatcher).launch {
        val actor = actorsMap[actorMapKey] ?: return@launch
        delay(ttl)
        actor.sendChannel.trySend(PoisonPill)
        while (actor.actorInstance.isStarted) {
            delay(1)
        }
        actorsMap.remove(actorMapKey)
    }

    private fun <T : Any> getShardKey(
        shardReference: ShardReference,
        message: T,
        shardActorRegisterInformation: ShardActorRegisterInformation<T>
    ): String {
        return "${shardReference.reference}:${
            shardActorRegisterInformation.shardBy(
                message
            )
        }"
    }
}
