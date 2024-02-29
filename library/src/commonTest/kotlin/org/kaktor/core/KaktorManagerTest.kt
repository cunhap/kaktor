package org.kaktor.core

import co.touchlab.kermit.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

sealed interface Command
data class TestCommand(val y: String): Command
data class TestAskCommand(val y: String): Command
data object AskForPropertyCommand: Command
data class Answer(val answer: String)

private val testingChannel = Channel<Any>()

private const val mockActorDefaultValue = 1

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

class KaktorManagerTest {

    private val kaktorManager = KaktorManager()

    @AfterTest
    fun clearManager() {
        actorsMap.clear()
        actorsRegisteredMap.clear()
    }

    @Test
    fun `when an actor is created, it's saved on the manager map`() = runTest {
        val actorReference = kaktorManager.createActor(
            ActorRegisterInformation(actorClass = MockKaktor::class)
        )

        assertTrue { kaktorManager.actorExists(actorReference) }
    }

    @Test
    fun `when an actor is attempted to be created again, it returns a new reference`() = runTest {
        val actorReference1 = kaktorManager.createActor(
            ActorRegisterInformation(actorClass = MockKaktor::class)
        )

        val actorReference2 = kaktorManager.createActor(
            ActorRegisterInformation(actorClass = MockKaktor::class)
        )

        assertTrue {
            kaktorManager.actorExists(actorReference1)
            kaktorManager.actorExists(actorReference2)
            actorReference1 != actorReference2
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `when a message is sent to an actor reference, it's handled by the implementation and an answer is received`() =
        runTest {
            val actorReference = kaktorManager.createActor(
                ActorRegisterInformation(actorClass = MockKaktor::class, actorStartupProperties = listOf(4))
            )
            flow {
                repeat(10) {
                    emit(TestCommand("message $it"))
                }
            }.onEach { actorReference.tell(it) }.toList()

            val responses = flow {
                repeat(10) {
                    emit(testingChannel.receive())
                }
            }.toList()

            assertFalse { responses.isEmpty() }
            assertTrue { responses.all { it is Answer } }
            assertTrue { responses.size == 10 }
            assertTrue { responses.all { (it as Answer).answer.startsWith("message") } }
            assertTrue { testingChannel.isEmpty }
        }

    @Test
    fun `when a message is sent that that actor doesn't recognize, it should be sent to error channel`() = runTest {
        val actorReference = kaktorManager.createActor(
            ActorRegisterInformation(actorClass = MockKaktor::class)
        )

        val command = "Hello world error"

        actorReference.tell(command)
        val normalResponse = withTimeoutOrNull(100) {
            testingChannel.receive()
        }
        val errorResponse = withTimeoutOrNull(100) {
            errorChannel.receive()
        }

        assertNull(normalResponse)
        assertNotNull(errorResponse)
        assertTrue { errorResponse is String }
        assertTrue { errorResponse == "Hello world error" }
    }

    @Test
    fun `when a message is sent to an unregistered actor, should throw ActorNotRegisteredException`() = runTest {
        assertFailsWith<ActorNotRegisteredException> {
            val command = TestCommand("message")
            val unregisteredActorReference = ActorReference(kaktorManager, "")
            unregisteredActorReference.tell(command)
        }
    }

    @Test
    fun `when ask method is called, response should be received from the actor`() = runTest {
        val actorReference = kaktorManager.createActor(
            ActorRegisterInformation(actorClass = MockKaktor::class, actorStartupProperties = listOf(5))
        )

        val command = TestAskCommand("message")
        val response = actorReference.ask(command)

        assertNotNull(response)
        assertTrue { response is  Answer}
        assertTrue { (response as Answer).answer == "Got your answer to question ${command.y}" }
    }

    @Test
    fun `when asked twice but first times out, first answer should be null, second should succeed`() = runTest {
        val actorReference = kaktorManager.createActor(
            ActorRegisterInformation(actorClass = MockKaktor::class)
        )

        val command = TestAskCommand("message")
        val response1 = actorReference.ask(command, 0)
        val response2 = actorReference.ask(command)

        assertNull(response1)
        assertNotNull(response2)
        assertTrue { response2 is  Answer}
        assertTrue { (response2 as Answer).answer == "Got your answer to question ${command.y}" }
    }

    @Test
    fun `when ask method is called for the property value, property should match the registration parameter`() = runTest {
        val actorReference1 = kaktorManager.createActor(
            ActorRegisterInformation(actorClass = MockKaktor::class, actorStartupProperties = listOf(5))
        )

        val actorReference2 = kaktorManager.createActor(
            ActorRegisterInformation(actorClass = MockKaktor::class)
        )

        val command = AskForPropertyCommand
        val response2 = actorReference2.ask(command)
        val response1 = actorReference1.ask(command)

        assertNotNull(response1)
        assertNotNull(response2)
        assertTrue { response1 == 5 }
        assertTrue { response2 == mockActorDefaultValue }
    }
}