package org.kaktor.core

import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.kaktor.core.commands.PoisonPill
import org.kaktor.core.ActorReference
import org.kaktor.core.Kaktor
import org.kaktor.core.KaktorManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KaktorTest {

    private lateinit var kaktor: TestKaktor
    private val actorChannel = Channel<Any>()

    @BeforeTest
    fun setup() {
        kaktor = TestKaktor()
        kaktor.receiveChannel = actorChannel
        kaktor.reference = ActorReference(mockk<KaktorManager>(), "Test")
    }

    @Test
    fun `start should start actor`(): Unit = runTest {
        val result = kaktor.start()

        assertTrue(result.isSuccess, "Actor should be started successfully")
        assertTrue(result.getOrThrow().isActive, "Actor should be active after start")
    }

    @Test
    fun `start should fail when called twice`(): Unit = runTest {
        kaktor.start()

        val result = kaktor.start()

        assertTrue(result.isFailure, "Second call to start should fail")
        assertTrue(result.exceptionOrNull() is IllegalStateException, "Exception should be IllegalStateException")
    }

    @Test
    fun behaviour(): Unit = runTest {
        kaktor.start()
        val message = "Test"
        actorChannel.send(message)

        delay(100)

        assertEquals(message, kaktor.receivedMessage, "Received message should be correct")
    }

    @Test
    fun `handleInternalMessage should stop actor`(): Unit = runTest {
        kaktor.start()
        actorChannel.send(PoisonPill)

        delay(100)

        assertFalse(kaktor.isStarted, "Actor should not be active after PoisonPill")
    }

    // You can add more tests for other cases based on your code

    private class TestKaktor: Kaktor<String>() {

        var receivedMessage: String? = null

        override suspend fun behaviour(message: String) {
            receivedMessage = message
        }
    }
}