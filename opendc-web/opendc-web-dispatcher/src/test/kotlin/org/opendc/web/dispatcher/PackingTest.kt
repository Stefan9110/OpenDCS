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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Packing decides what a dispatcher asks a cluster for, so the properties worth pinning are the
 * ones that cost real money or kill real runs: never asking for more memory than a slot holds,
 * never silently dropping work, and never sizing a bag from anything but its hungriest unit.
 */
class PackingTest {
    private val slot =
        Slot(
            cores = 4,
            memoryCeilingMb = 4096.0,
            timeBudgetSeconds = 600.0,
            jvmBaselineMb = 512.0,
            timeSafetyFactor = 3.0,
        )

    private fun unit(
        scenarioIndex: Int,
        seed: Long = 0,
        cpuSeconds: Double = 45.0,
        peakMemoryMb: Double = 256.0,
    ) = PlannedUnit(scenarioIndex, seed, cpuSeconds, peakMemoryMb)

    @Test
    @DisplayName("packs nothing into nothing rather than an empty bag")
    fun `empty input`() {
        assertEquals(emptyList<PlannedBag>(), packUnits(emptyList(), slot))
    }

    @Test
    @DisplayName("keeps every unit exactly once, so no work is dropped or run twice")
    fun `conserves units`() {
        val units = (0 until 40).map { unit(scenarioIndex = it, peakMemoryMb = 200.0 + it * 40.0) }

        val packed = packUnits(units, slot).flatMap { it.units }

        assertEquals(units.size, packed.size)
        assertEquals(units.toSet(), packed.toSet())
    }

    @Test
    @DisplayName("never requests more memory than the slot holds, for any mix of unit sizes")
    fun `stays under the ceiling`() {
        val units = (0 until 30).map { unit(scenarioIndex = it, peakMemoryMb = 100.0 + it * 100.0) }

        for (bag in packUnits(units, slot)) {
            val largest = bag.units.maxOf { it.peakMemoryMb }
            // A unit too large to ever fit is the one documented exception: it gets an honest
            // oversized request rather than being silently dropped or split.
            if (slot.jvmBaselineMb + largest <= slot.memoryCeilingMb) {
                assertTrue(
                    bag.memoryRequestMb <= slot.memoryCeilingMb,
                    "bag asked for ${bag.memoryRequestMb} MB against a ${slot.memoryCeilingMb} MB slot",
                )
            }
        }
    }

    @Test
    @DisplayName("sizes a bag from its hungriest unit, not its average")
    fun `memory follows the peak`() {
        val bags = packUnits(listOf(unit(0, peakMemoryMb = 3000.0), unit(1, peakMemoryMb = 100.0)), slot)

        val first = bags.first()
        assertEquals(1, first.parallelism, "3000 MB leaves no room for a second concurrent unit")
        assertEquals(slot.jvmBaselineMb + 3000.0, first.memoryRequestMb)
    }

    @Test
    @DisplayName("runs small units concurrently up to the core count, not beyond it")
    fun `parallelism is capped by cores`() {
        val units = (0 until 8).map { unit(scenarioIndex = it, peakMemoryMb = 64.0) }

        val parallelism = packUnits(units, slot).first().parallelism

        assertEquals(slot.cores, parallelism, "64 MB units fit far more than 4, but a slot has 4 cores")
    }

    @Test
    @DisplayName("gives a unit larger than the whole slot its own sequential bag")
    fun `oversized unit is isolated`() {
        val bags = packUnits(listOf(unit(0, peakMemoryMb = 10_000.0)), slot)

        assertEquals(1, bags.size)
        assertEquals(1, bags.first().parallelism)
        assertEquals(1, bags.first().units.size)
    }

    @Test
    @DisplayName("derives the time limit from the work in the bag and the safety factor")
    fun `time limit reflects the bag`() {
        val units = (0 until 4).map { unit(scenarioIndex = it, cpuSeconds = 100.0, peakMemoryMb = 64.0) }

        val bag = packUnits(units, slot).first()

        // Four 100s units at parallelism 4 is 100s of wall clock, tripled by the safety factor.
        assertEquals(4, bag.parallelism)
        assertEquals(400.0, bag.estimatedSeconds)
        assertEquals(300, bag.timeLimitSeconds)
    }

    @Test
    @DisplayName("is deterministic, so the same submission always plans the same bags")
    fun `stable ordering`() {
        val units = (0 until 25).map { unit(scenarioIndex = it % 5, seed = it.toLong(), peakMemoryMb = 256.0) }

        assertEquals(packUnits(units, slot), packUnits(units.shuffled(), slot))
    }
}
