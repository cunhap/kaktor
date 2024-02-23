package org.kaktor.common.commands

import kotlinx.coroutines.channels.SendChannel
import org.kaktor.common.ActorReference

sealed interface AutoHandledCommands

data object PoisonPill: AutoHandledCommands
data object RestartPill: AutoHandledCommands

data class AskCommand(val message: Any, val answerChannel: SendChannel<Any>)