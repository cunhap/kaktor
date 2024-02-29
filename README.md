# Multiplatform KAktor Library

## What is it?

The purpose of this library is to offer a way to use the Actor system design, where each processing function can be described as an Actor which processes a sequence of messages. The library is built entirely using Kotlin Coroutines and Channels and is completely async.

## How to create an actor

The only thing you need to do to use a basic Actor is to create a class that implements the `Kaktor` abstract class,
type it with what messages it will receive and needs to be able to process and then implement the `handleMessage` method.

Example:
            
```kotlin
sealed interface Command
data class TestCommand(val y: String) : Command
data class TestAskCommand(val y: String) : Command
data object AskForPropertyCommand : Command
data class Answer(val answer: String)

class MockKaktor(private val property1: Int = mockActorDefaultValue) : Kaktor<Command>() {
    override suspend fun handleMessage(message: Command): Any {
        Logger.i { "I'm actor with reference $self and my property is $property1" }
        return when (message) {
            is TestAskCommand -> {
                val answer = "Got your answer to question ${message.y}"
                Answer(answer)
            }
            is TestCommand -> {
                println("I'm handling a TestCommand that doesn't return anything")
            }
            is AskForPropertyCommand -> {
                println("Returning the property I was asked for")
                property1
            }
            else -> Unit
        }
    }
}
```

## How to send messages to Actor

After defining an actor, you need to register that actor in the system, by providing its class to the KaktorManager.
That returns a reference to that actor, and you need to use to it send it messages. You can use the fire-and-forget method
of `tell` or you can send a message with `ask` and wait for an answer. Since `ask` is a `suspend function` the code will
automatically suspend while the answer hasn't been returned.
The `ask` method will time out after `10s` but that timeout value is customizable when calling the function.
It's also possible to create multiple `ActorRef` for the same actor type by repeated calls to `KaktorManager.createActor`

Example:

```kotlin
val kaktorManager = KaktorManager()
val actorReference: ActorRef = kaktorManager.createActor(ActorRegisterInformation(actorClass = MockKaktor::class))

val tellCommand = TestCommand("message")
val askCommand = TestAskCommand("message")

actorReference.tell(testCommand) //This returns immediately
val response = actorReference.ask(askCommand) //returns an instance of Any that needs to be cast to the expected result
val responseTimedout = actorReference.ask(askCommand, 5000) //will time out after 5 seconds, returning null
```

## Sharding of actors

Up until now, you would create a new `ActorRef` for every new actor type you would like to create to handle your messages.
It was also possible to create multiple `ActorRef` for the same actor type by repeated calls to `KaktorManager.createActor`.
However, there are situation where you want to register an actor class but then have the messages distributed by a specific
attribute instead of all to the same `Kaktor` intance represented by the `ActorRef`. The `Sharding` functionalities removes
the burden from your side of having to manage all the different instances of your `Kaktors`.

The process of creating the `ActorRef` is similar, but instead of passing a `ActorRegisterInformation` you use `ShardActorRegisterInformation`.
The main difference is that the first attribute should be a `shardBy` function that the system will then use to decide to which
`Kaktor` it should deliver the message.

Example:

Let's say we have a `Kaktor` that represents an account. This actor has a balance and it supports three different types
of `AccountCommand` messages, one for adding balance, one for removing and one for retrieving. We also define a companion
object with the sharding function that, given a message, should return the shard referece for that actor, in this case,
we want a `Kaktor` for each `accountId`. 

```kotlin
    sealed interface AccountCommand {
        val accountId: String
    }

    data class AddBalance(override val accountId: String, val value: Long) : AccountCommand
    data class RemoveBalance(override val accountId: String, val value: Long) : AccountCommand
    data class RetrieveBalance(override val accountId: String) : AccountCommand

    class AccountActor : Kaktor<AccountCommand>() {
        private var balance: Long = 0

        override suspend fun handleMessage(message: AccountCommand): Any {
            return when(message) {
                is AddBalance -> balance += message.value
                is RemoveBalance -> balance -= message.value
                is RetrieveBalance -> balance
                else -> Unit
            }
        }

        companion object {
            fun shardByAccountId(message: AccountCommand): String {
                return message.accountId
            }
        }
    }
```

When creating a `Kaktor` then, we would pass a `ShardRegisterInformation` instead of a regular `ActorRegisterInformation`.
The main difference is that the first argument should be a reference to the sharding function of your actor.
Afterward, you would interact with the `ActorRef` (which is now an instante of `ShardReference`) in the same way as before,
taking into account that all messages that resolve to the same output of the `shardBy` functon will be received by the same actor.

```kotlin
val kaktorManager = KaktorManager()

val actorReference = kaktorManager.createActor(
    ShardActorRegisterInformation(shardBy = AccountActor::shardByAccountId, actorClass = AccountActor::class)
)

// Account Kaktor start with a balance of 0

val addBalanceAccount1 = AddBalance(accountId = "1", value = 10L) // Kaktor for account with id 1 gets an increment of 10
val addBalanceAccount2 = AddBalance(accountId = "2", value = 2L) // Kaktor for account with id 2 gets an increment of 2

//Notice that we use the same actorReference to send both messages
actorReference.tell(addBalanceAccount1)
actorReference.tell(addBalanceAccount2)

val retrieveBalanceAccount1 = RetrieveBalance(accountId = "1")
val retrieveBalanceAccount2 = RetrieveBalance(accountId = "2")

//And when we retrive each one's balance, we get the accturate results
val accountBalance2 = actorReference.ask(retrieveBalanceAccount2) 
val accountBalance1 = actorReference.ask(retrieveBalanceAccount1)

assertNotNull(accountBalance1)
assertNotNull(accountBalance2)
assertEquals(10L, accountBalance1 as Long)
assertEquals(2L, accountBalance2 as Long)

//Here we can see that an operation on Kaktor for account with id 1 does not interfere with the other one
val removeBalanceAccount1 = RemoveBalance(accountId = "1", value = 1L)
actorReference.tell(removeBalanceAccount1)

val refreshedAccountBalance1 = actorReference.ask(retrieveBalanceAccount1)
val refreshedAccountBalance2 = actorReference.ask(retrieveBalanceAccount2)

assertNotNull(refreshedAccountBalance1)
assertNotNull(refreshedAccountBalance2)
assertEquals(9L, refreshedAccountBalance1 as Long)
assertEquals(2L, refreshedAccountBalance2 as Long)
```

## Stopping an Actor

If you send a special `PoisonPill` message to any actor, they will stop processing.