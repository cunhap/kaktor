package org.kaktor.core

import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.Channel

sealed interface Command
data class TestCommand(val y: String): Command
data class TestAskCommand(val y: String): Command
data object AskForPropertyCommand: Command
data class Answer(val answer: String)

internal val testingChannel = Channel<Any>()

internal const val mockActorDefaultValue = 1

class MockKaktor(private val property1: Int = mockActorDefaultValue) : Kaktor<Command>() {
    override suspend fun behaviour(message: Command): Any {
        Logger.i { "I'm actor with reference $self and my property is $property1" }
        return when(message) {
            is TestAskCommand -> {
                val answer = "Got your answer to question ${message.y}"
                Answer(answer)
            }
            is TestCommand -> {
                testingChannel.send(Answer(message.y))
            }
            is AskForPropertyCommand -> {
                property1
            }
        }
    }
}



sealed interface AccountCommand {
    val accountId: String
}

data class AddBalance(override val accountId: String, val value: Long) : AccountCommand
data class RemoveBalance(override val accountId: String, val value: Long) : AccountCommand
data class RetrieveBalance(override val accountId: String) : AccountCommand

class AccountActor : Kaktor<AccountCommand>() {
    private var balance: Long = 0

    override suspend fun behaviour(message: AccountCommand): Any {
        return when(message) {
            is AddBalance -> balance += message.value
            is RemoveBalance -> balance -= message.value
            is RetrieveBalance -> balance
        }
    }

    companion object {
        fun shardByAccountId(message: AccountCommand): String {
            return message.accountId
        }
    }
}