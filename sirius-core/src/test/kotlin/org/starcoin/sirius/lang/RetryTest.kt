package org.starcoin.sirius.lang

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RetryTest {

    @Test
    fun testRetryTimeoutOrNull() = runBlocking {
        val timeout = 2000L
        val period = 100L
        val startTime = System.currentTimeMillis()
        val count = AtomicInteger()
        val num = retryWithTimeoutOrNull(timeout, period) { count.incrementAndGet(); null }
        val endTime = System.currentTimeMillis()
        val useTime = endTime - startTime
        //println("$useTime ${count.get()}")
        Assert.assertTrue(useTime < timeout + 200)
        Assert.assertNull(num)
        Assert.assertTrue(count.get() >= timeout / period - 2)
    }

    private fun returnNUll(): Any? {
        return null
    }

    @Test
    fun testRetryTimeout() = runBlocking {
        val timeout = 2000L
        val startTime = System.currentTimeMillis()
        try {
            retryWithTimeout(timeout, 100) { returnNUll() }
            Assert.fail("expect timeout")
        } catch (e: TimeoutCancellationException) {
        }
        val endTime = System.currentTimeMillis()
        val useTime = endTime - startTime
        Assert.assertTrue(useTime < timeout + 200)
    }
}