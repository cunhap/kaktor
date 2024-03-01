package org.kaktor.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PassivationTest {

    private val accountActorRegisterInformation = ShardActorRegisterInformation<AccountCommand>(
        shardBy = AccountActor::shardByAccountId,
        actorClass = AccountActor::class,
        passivation = 50,
        actorStartupProperties = listOf()
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `when an actor doesn't receive a message for over the passivation limit, it gets removed from the actor map`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val kaktorManager = KaktorManager(dispatcher = testDispatcher)
            val accountActors = kaktorManager.createActor(accountActorRegisterInformation)

            val addBalanceCommand = AddBalance("account1", 10L)
            val actorKey = "${AccountActor::class.qualifiedName}:account1"
            accountActors.tell(addBalanceCommand)
            advanceTimeBy(10)
            assertTrue { actorsMap.containsKey(actorKey) }
            val actorInstance = actorsMap[actorKey]!!.actorInstance
            advanceTimeBy(50)
            assertFalse { actorsMap.containsKey(actorKey) }
            assertFalse { actorInstance.isStarted }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `when an actor receives a second message, it should reset passivation`() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val kaktorManager = KaktorManager(dispatcher = testDispatcher)
            val accountActors = kaktorManager.createActor(accountActorRegisterInformation)

            val addBalanceCommand = AddBalance("account1", 10L)
            val actorKey = "${AccountActor::class.qualifiedName}:account1"

            accountActors.tell(addBalanceCommand)
            val actorInstance = actorsMap[actorKey]!!.actorInstance
            advanceTimeBy(45)
            assertTrue { actorsMap.containsKey(actorKey) }
            assertTrue { actorInstance.isStarted }

            accountActors.tell(addBalanceCommand)
            advanceTimeBy(45)
            assertTrue { actorsMap.containsKey(actorKey) }

            advanceTimeBy(60)
            assertFalse { actorsMap.containsKey(actorKey) }
            assertFalse { actorInstance.isStarted }
        }
}