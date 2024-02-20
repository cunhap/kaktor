package org.kaktor.common.commands

import kotlinx.coroutines.channels.SendChannel
import org.kaktor.common.ActorReference

sealed interface AutoHandledCommands

data object PoisonPill: AutoHandledCommands
data object RestartPill: AutoHandledCommands

data class AskCommand<T>(val message: T, val answerChannel: SendChannel<Any>)

interface Command