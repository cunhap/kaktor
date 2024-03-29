package org.kaktor.core

import co.touchlab.kermit.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.kaktor.core.commands.AskCommand
import org.kaktor.core.commands.AutoHandledCommands
import org.kaktor.core.commands.PoisonPill
import org.kaktor.core.commands.RestartPill

/**
 * Abstract class representing an actor for message handling.
 *
 * @param T The type of messages this actor can handle.
 */
abstract class Kaktor<T : Any> {

    private val messageHandlingJob = SupervisorJob()

    var isStarted: Boolean = false
        private set

    internal lateinit var receiveChannel: ReceiveChannel<Any>
    internal lateinit var answerChannel: SendChannel<Any>
    internal lateinit var reference: ActorRef

    protected val self by lazy { reference }

    /**
     * Starts the actor and returns a Result object containing a Job if the actor started successfully,
     * or a failure Result if the actor has already started or some attributes need to be set before-hand.
     *
     * @return A Result object containing a Job if the actor started successfully, or a failure Result with an exception.
     * @throws IllegalStateException if some attributes need to be set before-hand.
     */
    internal suspend fun start(): Result<Job> {
        if (!checkIfInitialized().first) {
            throw IllegalStateException("Some attributes need to be set before-hand")
        }

        if (isStarted) return Result.failure(IllegalStateException("Actor $self has already started"))

        coroutineScope {
            launch(messageHandlingJob) {
                receiveChannel.consumeEach {
                    when (it) {
                        is AutoHandledCommands -> internalHandleMessage(it)
                        else -> {
                            try {
                                Logger.d { "Received message $it" }
                                val message = if (it is AskCommand) it.message else it

                                @Suppress("UNCHECKED_CAST")
                                val response = behaviour(message as T)
                                if (it is AskCommand) {
                                    Logger.d { "Answering with response $response" }
                                    it.answerChannel.trySend(response).onFailure { failSend ->
                                        Logger.e(failSend) { "I, $self, failed to answer message $response" }
                                    }
                                }
                            } catch (exception: ClassCastException) {
                                errorChannel.send(it)
                            }
                        }
                    }
                }
            }.invokeOnCompletion {
                Logger.d(it) { "Handling coroutine for actor $self has terminated" }
            }
        }

        isStarted = true
        return Result.success(messageHandlingJob)
    }

    /**
     * Handles a message of type T.
     *
     * @param message The message to be handled.
     * @return The result of handling the message.
     */
    abstract suspend fun behaviour(message: T): Any

    private suspend fun internalHandleMessage(message: AutoHandledCommands) {
        when (message) {
            is PoisonPill -> stopActor()
            is RestartPill -> {
                stopActor()
                start()
            }
        }
    }

    private fun checkIfInitialized(): Pair<Boolean, List<String>?> {
        val uninitialized = mutableListOf<String>()
        if (!this::receiveChannel.isInitialized) {
            uninitialized.add(this::receiveChannel.name)
        }
        if (!this::reference.isInitialized) {
            uninitialized.add(this::reference.name)
        }
        return Pair(uninitialized.isEmpty(), uninitialized)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    protected suspend fun stopActor() {
        Logger.d { "Actor $self has been stopped" }
        isStarted = false
        try {
            withTimeout(10000) {
                while (!receiveChannel.isEmpty) {
                    delay(10)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            Logger.e(
                "operation=terminateHandlerChannel, message='Actor $self timed out when " +
                        "shutting down', isEmpty=${receiveChannel.isEmpty}", timeout
            )
        }
    }

}
