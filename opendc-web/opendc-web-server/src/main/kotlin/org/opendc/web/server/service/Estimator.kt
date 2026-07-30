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

package org.opendc.web.server.service

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.inject.Singleton
import kotlinx.serialization.Serializable
import org.opendc.sdk.model.experiment.ScenarioSpec
import org.opendc.sdk.model.topology.TopologySpec
import org.opendc.web.dispatcher.Slot

@ConfigMapping(prefix = "opendc.execution")
interface ExecutionConfig {
    /** Baseline runtime estimate for one (scenario, seed) run, pending trace-metadata inputs. */
    @WithDefault("45.0")
    fun secondsPerRun(): Double

    /** Fixed per-unit memory floor: engine bookkeeping independent of topology size. */
    @WithDefault("256.0")
    fun unitBaseMemoryMb(): Double

    /** Memory added per simulated host: state, telemetry rows, export buffers. */
    @WithDefault("1.0")
    fun memoryPerHostMb(): Double

    /** JVM overhead of one launcher process, shared by every unit it runs. */
    @WithDefault("512.0")
    fun jvmBaselineMb(): Double

    /** Cores one execution slot gets; the cap on per-bag parallelism. */
    @WithDefault("4")
    fun slotCores(): Int

    /** Memory ceiling one execution slot should stay under. */
    @WithDefault("4096.0")
    fun slotMemoryCeilingMb(): Double

    /** Target wall-clock ceiling for one execution slot. */
    @WithDefault("600.0")
    fun slotTimeBudgetSeconds(): Double

    /** Multiplier from estimated runtime to the enforced time limit. */
    @WithDefault("3.0")
    fun timeSafetyFactor(): Double
}

/**
 * What one (scenario, seed) run is expected to cost. Peak memory is the packing-critical
 * dimension: overshooting time means a slow job, overshooting memory means an OOM kill.
 */
data class UnitEstimate(
    val cpuSeconds: Double,
    val peakMemoryMb: Double,
)

/** The user-facing billing estimate. Billing is in simulation seconds, never in memory. */
@Serializable
data class CostEstimate(
    val scenarioCount: Int,
    val estimatedSimulationSeconds: Double,
    val estimatedBudgetSeconds: Double,
)

/**
 * Predicts execution cost from a spec alone. The memory term uses the topology, which is inline
 * in the spec at submit time; the runtime term is a flat constant until trace metadata (task and
 * fragment counts) exists to shape it. Seeds of one scenario share an estimate: the seed changes
 * the sampled workload, not the memory shape.
 */
@Singleton
class Estimator(private val config: ExecutionConfig) {
    fun estimateUnit(scenario: ScenarioSpec): UnitEstimate =
        UnitEstimate(
            cpuSeconds = config.secondsPerRun(),
            peakMemoryMb = config.unitBaseMemoryMb() + config.memoryPerHostMb() * hostCount(scenario.topology),
        )

    fun estimate(scenarios: List<ScenarioSpec>): CostEstimate {
        val simulationSeconds = scenarios.sumOf { it.runs * estimateUnit(it).cpuSeconds }
        return CostEstimate(
            scenarioCount = scenarios.size,
            estimatedSimulationSeconds = simulationSeconds,
            estimatedBudgetSeconds = simulationSeconds,
        )
    }

    fun slot(): Slot =
        Slot(
            cores = config.slotCores(),
            memoryCeilingMb = config.slotMemoryCeilingMb(),
            timeBudgetSeconds = config.slotTimeBudgetSeconds(),
            jvmBaselineMb = config.jvmBaselineMb(),
            timeSafetyFactor = config.timeSafetyFactor(),
        )

    private fun hostCount(topology: TopologySpec): Int =
        topology.clusters.sumOf { cluster -> cluster.count * cluster.hosts.sumOf { it.count } }
}
