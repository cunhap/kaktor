# Multiplatform KAktor Library

## What is it?

The purpose of this library is to offer a way to use the Actor system design, where each processing function can be described as an Actor which processes a sequence of messages. The library is built entirely using Kotlin Coroutines and Channels and is completely async.

## How to create an actor

The only think you need to do to use a basic Actor is to create a class that implements the `Kaktor` abstract class,
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
        }
    }
}
```

## How to send messages to Actor

After defining an actor, you need to register that actor in the system, by providing its class to the KaktorManager.
That returns a reference to that actor and you nede to use to it send it messages. You can use the fire-and-forget method
of `tell` or you can send a message with `ask` and wait for an answer. Since `ask` is a `suspend function` the code will
automatically suspend while the answer hasn't been returned.
The `ask` method will time out after `10s` but that timeout value is customizable when calling the function.

Example:

```kotlin
val actorReference = KaktorManager.createActor(ActorRegisterInformation(actorClass = MockKaktor::class))

val tellCommand = TestCommand("message")
val askCommand = TestAskCommand("message")

actorReference.tell(testCommand) //This returns immediately
val response = actorReference.ask(askCommand) //returns an instance of Any that needs to be cast to the expected result
val responseTimedout = actorReference.ask(askCommand, 5000) //will time out after 5 seconds, returning null
```

## Stopping an Actor

If you send a special `PoisonPill` message to any actor, they will stop processing.