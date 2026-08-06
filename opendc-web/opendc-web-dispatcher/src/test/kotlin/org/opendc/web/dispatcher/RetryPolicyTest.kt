/*
 * Copyright (c) 2026 AtLarge Research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.opendc.web.dispatcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Retrying costs a slot, so what matters is that a failure which cannot improve is never retried,
 * that escalation addresses the resource that actually ran out, and that it terminates.
 */
class RetryPolicyTest {
    private val slot = ExecutionSlot(cores = 4, memoryMb = 4096.0)
    private val policy =
        DispatchPolicy(
            jvmBaselineMb = 512.0,
            startupSeconds = 30.0,
            timeSafetyFactor = 3.0,
            maxAttempts = 3,
            growthFactor = 2.0,
            maxMemoryRequestMb = 32_768.0,
        )

    private fun failed(
        unitCount: Int = 4,
        memoryRequestMb: Double = 2560.0,
        timeLimitSeconds: Int = 300,
    ): PlannedBag {
        val units = (0 until unitCount).map { PlannedUnit(it, 0, 100.0, 512.0) }
        return PlannedBag(
            units = units,
            parallelism = unitCount,
            heapMb = memoryRequestMb - policy.jvmBaselineMb,
            memoryRequestMb = memoryRequestMb,
            timeLimitSeconds = timeLimitSeconds,
            makespanSeconds = 100.0,
        )
    }

    @ParameterizedTest
    @EnumSource(value = ExitReason::class, names = ["SIMULATION_ERROR", "INVALID_SPEC", "CANCELLED", "OK"])
    fun `never retries a failure that a different grant cannot change`(reason: ExitReason) {
        assertEquals(emptyList<PlannedBag>(), retryBags(failed(), attempt = 1, reason, slot, policy))
    }

    @ParameterizedTest
    @EnumSource(value = ExitReason::class, names = ["OOM", "TIMEOUT", "WALLTIME", "UNKNOWN"])
    fun `stops retrying once the attempt cap is reached, whatever the reason`(reason: ExitReason) {
        assertEquals(emptyList<PlannedBag>(), retryBags(failed(), policy.maxAttempts, reason, slot, policy))
    }

    @Test
    fun `answers an out-of-memory kill by holding fewer runs at once`() {
        val retries = retryBags(failed(unitCount = 4), attempt = 1, ExitReason.OOM, slot, policy)

        assertTrue(retries.size > 1, "a bag of four should come back as more than one bag")
        assertEquals(4, retries.sumOf { it.units.size }, "splitting must not drop work")
        for (bag in retries) {
            assertTrue(bag.units.size < 4, "each bag should hold fewer units than the one that failed")
        }
    }

    @Test
    fun `asks for more memory only once a bag is down to a single run`() {
        val bag = failed(unitCount = 1)

        val retry = retryBags(bag, attempt = 1, ExitReason.OOM, slot, policy).single()

        assertEquals(bag.memoryRequestMb * policy.growthFactor, retry.memoryRequestMb)
        assertEquals(1, retry.units.size)
    }

    @Test
    fun `gives up rather than asking for a grant nothing will place`() {
        val bag = failed(unitCount = 1, memoryRequestMb = policy.maxMemoryRequestMb)

        assertEquals(emptyList<PlannedBag>(), retryBags(bag, attempt = 1, ExitReason.OOM, slot, policy))
    }

    @Test
    fun `answers an overrun with more time, since splitting a bag does not shorten it`() {
        val bag = failed(unitCount = 4)

        val retry = retryBags(bag, attempt = 1, ExitReason.TIMEOUT, slot, policy).single()

        assertEquals(4, retry.units.size, "every unit still runs at once, so the bag stays whole")
        assertTrue(retry.timeLimitSeconds > bag.timeLimitSeconds, "an overrun needs longer, not smaller")
        assertEquals(bag.memoryRequestMb, retry.memoryRequestMb, "time ran out, not memory")
    }

    @Test
    fun `retries a platform that gave no verdict with the shape it already had`() {
        val bag = failed()

        val retry = retryBags(bag, attempt = 1, ExitReason.UNKNOWN, slot, policy).single()

        assertEquals(bag, retry)
    }
}
