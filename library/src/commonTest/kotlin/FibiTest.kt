import kotlin.test.*

class FibiTest {

    @Test
    fun `test 3rd element`() {
        assertEquals(firstElement + secondElement, fibi.take(3).last())
    }
}