package org.kaktor.common

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

data class TestCommand(val y: String)
data class Answer(val answer: String)

private val testingChannel = Channel<Any>()

class MockKaktor : Kaktor<TestCommand>() {
    override suspend fun handleMessage(message: TestCommand) {
        Logger.i { "I'm actor with reference $self" }
        testingChannel.send(Answer(message.y))
    }
}

class KaktorManagerTest {

    @AfterTest
    fun clearManager() {
        actorsMap.clear()
        actorsRegisteredMap.clear()
    }

    @Test
    fun `when an actor is created, it's saved on the manager map`() = runTest {
        val actorReference = KaktorManager.createActor(
            ActorRegisterInformation(actorClass = MockKaktor::class)
        )

        assertTrue { KaktorManager.actorExists(actorReference) }
    }

    @Test
    fun `when an actor is attempted to be created again, it returns a new reference`() = runTest {
        val actorReference1 = KaktorManager.createActor(
            ActorRegisterInformation(actorClass = MockKaktor::class)
        )

        val actorReference2 = KaktorManager.createActor(
            ActorRegisterInformation(actorClass = MockKaktor::class)
        )

        assertTrue {
            KaktorManager.actorExists(actorReference1)
            KaktorManager.actorExists(actorReference2)
            actorReference1 != actorReference2
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `when a message is sent to an actor reference, it's handled by the implementation and an answer is received`() =
        runTest {
            val actorReference = KaktorManager.createActor(
                ActorRegisterInformation(actorClass = MockKaktor::class)
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
        val actorReference = KaktorManager.createActor(
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
            val unregisteredActorReference = ActorReference()
            unregisteredActorReference.tell(command)
        }
    }
}