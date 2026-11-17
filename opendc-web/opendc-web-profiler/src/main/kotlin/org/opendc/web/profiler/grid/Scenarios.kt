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

package org.opendc.web.profiler.grid

import org.opendc.sdk.model.dsl.ghz
import org.opendc.sdk.model.dsl.gib
import org.opendc.sdk.model.dsl.minutes
import org.opendc.sdk.model.experiment.ScenarioSpec
import org.opendc.sdk.model.resource.ResourceReference
import org.opendc.sdk.model.resource.UriReference
import org.opendc.sdk.model.scheduler.PrefabAllocationPolicySpec
import org.opendc.sdk.model.telemetry.ExportSpec
import org.opendc.sdk.model.topology.ClusterSpec
import org.opendc.sdk.model.topology.CpuSpec
import org.opendc.sdk.model.topology.DataCenterSpec
import org.opendc.sdk.model.topology.HostSpec
import org.opendc.sdk.model.topology.MemorySpec
import org.opendc.sdk.model.topology.TopologySpec
import org.opendc.sdk.model.workload.EfficientTraceWorkloadSpec
import org.opendc.sdk.model.workload.TraceWorkloadSpec
import org.opendc.sdk.model.workload.WorkloadSpec
import org.opendc.web.dispatcher.estimate.EstimatorCoefficients
import org.opendc.web.dispatcher.estimate.TraceExtent
import org.opendc.web.dispatcher.estimate.TraceSizeEstimator
import org.opendc.web.dispatcher.estimate.UnitEstimate
import org.opendc.web.profiler.trace.TraceShape

/** Where a point's trace is staged inside the launcher's working directory. */
const val STAGED_TRACE = "inputs/0"

private const val CORES_PER_HOST = 16

private const val CORE_GHZ = 2

private const val MEMORY_GIB = 128

/**
 * The scenario a point runs: one cluster of identical hosts, the point's trace, loader, sampling
 * and export settings, the default scheduler, and nothing else that could move the cost.
 */
fun GridPoint.scenario(
    seed: Int,
    source: ResourceReference = UriReference(STAGED_TRACE),
): ScenarioSpec =
    ScenarioSpec(
        topology = topology(hosts),
        workload = workload(source),
        allocationPolicy = PrefabAllocationPolicySpec(),
        exportModel =
            ExportSpec(
                exportInterval = exportIntervalMinutes.minutes,
                printFrequency = null,
                filesToExport = tables.files,
            ),
        runs = 1,
        initialSeed = seed,
        id = 0,
    )

/** What the server's unfitted model would grant this point, from the same scenario and the trace's rows. */
fun shippedPrediction(
    point: GridPoint,
    shape: TraceShape,
): UnitEstimate =
    TraceSizeEstimator(EstimatorCoefficients.SHIPPED)
        .estimate(point.scenario(seed = 0), TraceExtent(taskCount = shape.tasks, fragmentCount = shape.fragments))

private fun GridPoint.workload(source: ResourceReference): WorkloadSpec =
    if (trace.efficient) {
        EfficientTraceWorkloadSpec(source = source, sampleFraction = sampleFraction)
    } else {
        TraceWorkloadSpec(source = source, sampleFraction = sampleFraction)
    }

private fun topology(hosts: Int): TopologySpec =
    TopologySpec(
        listOf(
            DataCenterSpec(
                clusters =
                    listOf(
                        ClusterSpec(
                            hosts =
                                listOf(
                                    HostSpec(
                                        count = hosts,
                                        cpu = CpuSpec(coreCount = CORES_PER_HOST, coreSpeed = CORE_GHZ.ghz),
                                        memory = MemorySpec(size = MEMORY_GIB.gib),
                                    ),
                                ),
                        ),
                    ),
            ),
        ),
    )
