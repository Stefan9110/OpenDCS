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

import kotlin.math.ceil
import kotlin.math.floor

/** One (scenario, seed) run with its cost estimate attached. */
data class PlannedUnit(
    val scenarioIndex: Int,
    val seed: Long,
    val cpuSeconds: Double,
    val peakMemoryMb: Double,
)

/** The execution slot shape bags are packed for. */
data class Slot(
    val cores: Int,
    val memoryCeilingMb: Double,
    val timeBudgetSeconds: Double,
    val jvmBaselineMb: Double,
    val timeSafetyFactor: Double,
)

/**
 * One packed execution: its units, the concurrency it will run at, and the resource grant the
 * dispatcher requests for it.
 */
data class PlannedBag(
    val units: List<PlannedUnit>,
    val parallelism: Int,
    val memoryRequestMb: Double,
    val timeLimitSeconds: Int,
    val estimatedSeconds: Double,
)

/**
 * Packs units into bags, memory-first. Units are sorted by peak memory descending, so each bag is
 * memory-homogeneous and its first unit dictates both the parallelism that fits under the slot
 * ceiling and the memory request. Time then fills the bag: parallelism * timeBudget of unit
 * seconds. Within a bag P units run concurrently, so peak memory composes as
 * jvmBaseline + P * max(unit peak) while runtime composes as sum / P; memory is the dimension
 * that kills (OOM), time only slows, hence memory drives the shape.
 *
 * A unit too large for the ceiling still gets its own sequential bag with an honest oversized
 * request; refusing or escalating it is dispatcher policy, not packing policy.
 */
fun packUnits(
    units: List<PlannedUnit>,
    slot: Slot,
): List<PlannedBag> {
    val remaining =
        units.sortedWith(
            compareByDescending<PlannedUnit> { it.peakMemoryMb }.thenBy { it.scenarioIndex }.thenBy { it.seed },
        )
    val bags = mutableListOf<PlannedBag>()
    var index = 0

    while (index < remaining.size) {
        val head = remaining[index]
        val parallelism = parallelismFor(head.peakMemoryMb, slot)
        val capacitySeconds = parallelism * slot.timeBudgetSeconds
        val bag = mutableListOf(head)
        var bagSeconds = head.cpuSeconds

        index++

        while (index < remaining.size && bagSeconds + remaining[index].cpuSeconds <= capacitySeconds) {
            bag.add(remaining[index])
            bagSeconds += remaining[index].cpuSeconds
            index++
        }

        bags.add(
            PlannedBag(
                units = bag,
                parallelism = parallelism,
                memoryRequestMb = slot.jvmBaselineMb + parallelism * head.peakMemoryMb,
                timeLimitSeconds = ceil(slot.timeSafetyFactor * bagSeconds / parallelism).toInt(),
                estimatedSeconds = bagSeconds,
            ),
        )
    }
    return bags
}

private fun parallelismFor(
    unitPeakMb: Double,
    slot: Slot,
): Int {
    val memoryRoom = floor((slot.memoryCeilingMb - slot.jvmBaselineMb) / unitPeakMb).toInt()
    return memoryRoom.coerceIn(1, slot.cores)
}
