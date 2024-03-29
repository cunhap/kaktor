package org.kaktor.core

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull


class ShardingTest {
    private val kaktorManager = KaktorManager()

    @AfterTest
    fun clearManager() {
        actorsMap.clear()
        actorsRegisteredMap.clear()
    }

    @Test
    fun `should send message to the same actor based on sharding function`() = runTest {
        val actorReference = kaktorManager.createActor(
            ShardActorRegisterInformation(shardBy = AccountActor::shardByAccountId, actorClass = AccountActor::class) {
                    AccountActor()
            }
        )

        val addBalanceAccount1 = AddBalance(accountId = "1", value = 10L)
        val addBalanceAccount2 = AddBalance(accountId = "2", value = 2L)

        actorReference.tell(addBalanceAccount1)
        actorReference.tell(addBalanceAccount2)

        val retrieveBalanceAccount1 = RetrieveBalance(accountId = "1")
        val retrieveBalanceAccount2 = RetrieveBalance(accountId = "2")

        val accountBalance2 = actorReference.ask(retrieveBalanceAccount2)
        val accountBalance1 = actorReference.ask(retrieveBalanceAccount1)

        assertNotNull(accountBalance1)
        assertNotNull(accountBalance2)
        assertEquals(10L, accountBalance1 as Long)
        assertEquals(2L, accountBalance2 as Long)

        val removeBalanceAccount1 = RemoveBalance(accountId = "1", value = 1L)
        actorReference.tell(removeBalanceAccount1)

        val refreshedAccountBalance1 = actorReference.ask(retrieveBalanceAccount1)
        val refreshedAccountBalance2 = actorReference.ask(retrieveBalanceAccount2)

        assertNotNull(refreshedAccountBalance1)
        assertNotNull(refreshedAccountBalance2)
        assertEquals(9L, refreshedAccountBalance1 as Long)
        assertEquals(2L, refreshedAccountBalance2 as Long)
    }
}