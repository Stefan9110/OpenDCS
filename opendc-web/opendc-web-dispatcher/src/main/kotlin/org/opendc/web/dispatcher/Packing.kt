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
import kotlin.math.min

/** One `(scenario, seed)` run with what it is expected to cost. */
data class PlannedUnit(
    val scenarioIndex: Int,
    val seed: Long,
    val cpuSeconds: Double,
    val peakMemoryMb: Double,
) {
    init {
        require(peakMemoryMb > 0.0) { "unit $scenarioIndex/$seed was estimated at $peakMemoryMb MB" }
        require(cpuSeconds >= 0.0) { "unit $scenarioIndex/$seed was estimated at $cpuSeconds s" }
    }
}

/**
 * One packed execution.
 *
 * @property heapMb What the units need between them, which is the process heap.
 * @property memoryRequestMb What the platform must reserve, heap plus the process itself.
 * @property makespanSeconds Expected wall clock, which elapsed time is later compared against.
 */
data class PlannedBag(
    val units: List<PlannedUnit>,
    val parallelism: Int,
    val heapMb: Double,
    val memoryRequestMb: Double,
    val timeLimitSeconds: Int,
    val makespanSeconds: Double,
)

/**
 * What a bag is shaped against, and how far a failing one may be escalated before its work is given
 * up on.
 *
 * @property jvmBaselineMb The launcher's own footprint, paid once however many units share it.
 * @property startupSeconds What a launcher costs before its first run begins, likewise paid once.
 * @property timeSafetyFactor How far past its estimate the work in a bag may run before it is killed.
 * @property maxAttempts Including the first, so two means one retry.
 * @property growthFactor What a grant is multiplied by when the work needs more than it was given.
 * @property maxMemoryRequestMb The largest grant worth asking for.
 */
data class DispatchPolicy(
    val jvmBaselineMb: Double,
    val startupSeconds: Double,
    val timeSafetyFactor: Double,
    val maxAttempts: Int,
    val growthFactor: Double,
    val maxMemoryRequestMb: Double,
)

/**
 * Packs units into bags, memory first.
 *
 * Units are ordered by peak memory and each bag is filled from the top, so a bag is
 * memory-homogeneous and its first unit decides both how many fit and what to ask for.
 *
 * A bag runs every unit it holds at once, so its parallelism is its size and its expected duration
 * is `max(unit)` rather than `sum / n`. A unit too large for the slot gets a bag of its own with an
 * oversized request.
 */
fun planBags(
    units: List<PlannedUnit>,
    slot: ExecutionSlot,
    policy: DispatchPolicy,
): List<PlannedBag> {
    val ordered =
        units.sortedWith(
            compareByDescending<PlannedUnit> { it.peakMemoryMb }.thenBy { it.scenarioIndex }.thenBy { it.seed },
        )
    val bags = mutableListOf<PlannedBag>()
    var index = 0

    while (index < ordered.size) {
        val head = ordered[index]
        val fits = floor((slot.memoryMb - policy.jvmBaselineMb) / head.peakMemoryMb).coerceIn(1.0, slot.cores.toDouble())
        val size = min(fits.toInt(), ordered.size - index)
        val bag = ordered.subList(index, index + size).toList()
        bags.add(bag.toBag(policy.jvmBaselineMb + size * head.peakMemoryMb, policy))
        index += size
    }
    return bags
}

/**
 * How a failed execution is tried again, or an empty list when nothing different is worth trying.
 *
 * Failures a different grant cannot change are given up on at once. For the rest, the lever depends
 * on which resource ran out: a bag's memory is `baseline + n x peak` while its duration is its
 * longest single unit, so splitting it halves what it holds and does not shorten it. Memory is
 * answered by splitting, or by a larger grant once a bag is down to one unit; time only by a longer
 * limit.
 *
 * Every attempt writes the same deterministic output, so a retry overwrites what it replaces.
 */
fun retryBags(
    bag: PlannedBag,
    attempt: Int,
    reason: ExitReason,
    slot: ExecutionSlot,
    policy: DispatchPolicy,
): List<PlannedBag> {
    if (!reason.isResourceShaped || attempt >= policy.maxAttempts) {
        return emptyList()
    }
    return when (reason) {
        ExitReason.OOM ->
            if (bag.units.size > 1) {
                split(bag, slot, policy)
            } else {
                enlarged(bag, policy, memoryRequestMb = bag.memoryRequestMb * policy.growthFactor)
            }

        ExitReason.TIMEOUT, ExitReason.WALLTIME ->
            enlarged(
                bag,
                policy,
                timeLimitSeconds = ceil(bag.timeLimitSeconds * policy.growthFactor).toInt(),
            )

        // Nothing is known about why it died, so it is tried once more as it was.
        ExitReason.UNKNOWN -> listOf(bag)

        ExitReason.OK, ExitReason.SIMULATION_ERROR, ExitReason.INVALID_SPEC, ExitReason.CANCELLED -> emptyList()
    }
}

/** The same work in more bags, each holding at most half of what did not fit. */
private fun split(
    bag: PlannedBag,
    slot: ExecutionSlot,
    policy: DispatchPolicy,
): List<PlannedBag> {
    val half = ceil(bag.units.size / 2.0).toInt()
    val bags = planBags(bag.units, slot.copy(cores = min(slot.cores, half)), policy)
    return if (bags.size > 1) {
        bags
    } else {
        enlarged(bag, policy, memoryRequestMb = bag.memoryRequestMb * policy.growthFactor)
    }
}

private fun enlarged(
    bag: PlannedBag,
    policy: DispatchPolicy,
    memoryRequestMb: Double = bag.memoryRequestMb,
    timeLimitSeconds: Int = bag.timeLimitSeconds,
): List<PlannedBag> =
    if (memoryRequestMb > policy.maxMemoryRequestMb) {
        emptyList()
    } else {
        listOf(
            bag.copy(
                heapMb = (memoryRequestMb - policy.jvmBaselineMb).coerceAtLeast(1.0),
                memoryRequestMb = memoryRequestMb,
                timeLimitSeconds = timeLimitSeconds,
            ),
        )
    }

private fun List<PlannedUnit>.toBag(
    memoryRequestMb: Double,
    policy: DispatchPolicy,
): PlannedBag {
    val makespan = maxOf { it.cpuSeconds }
    return PlannedBag(
        units = this,
        parallelism = size,
        heapMb = memoryRequestMb - policy.jvmBaselineMb,
        memoryRequestMb = memoryRequestMb,
        // Starting a launcher costs the same whatever it then runs, so it is added rather than
        // multiplied: a factor over a short bag's estimate is all startup and no work.
        timeLimitSeconds = ceil(policy.startupSeconds + policy.timeSafetyFactor * makespan).toInt().coerceAtLeast(1),
        makespanSeconds = makespan,
    )
}
