package org.kaktor.common

import co.touchlab.kermit.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.kaktor.common.commands.AutoHandledCommands
import org.kaktor.common.commands.PoisonPill
import org.kaktor.common.commands.RestartPill

interface Ikaktor {
    suspend fun start(): Result<Job>
}

abstract class Kaktor<T : Any> : Ikaktor {

    private val messageHandlingJob = SupervisorJob()

    var isStarted: Boolean = false
        private set

    internal lateinit var receiveChannel: ReceiveChannel<Any>
    internal lateinit var answerChannel: SendChannel<Any>

    internal lateinit var reference: String

    protected val self by lazy { reference }

    override suspend fun start(): Result<Job> {
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
                            try{
                                @Suppress("UNCHECKED_CAST")
                                handleMessage(it as T)
                            } catch (exception: ClassCastException) {
                                errorChannel.send(it)
                            }
                        }
                    }
                }
            }
        }

        isStarted = true
        return Result.success(messageHandlingJob)
    }

    abstract suspend fun handleMessage(message: T)

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
    private suspend fun stopActor() {
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
