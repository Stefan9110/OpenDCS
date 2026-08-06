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

package org.opendc.web.dispatcher.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opendc.sdk.model.dsl.ghz
import org.opendc.sdk.model.dsl.gib
import org.opendc.sdk.model.experiment.ScenarioSpec
import org.opendc.sdk.model.resource.NamedReference
import org.opendc.sdk.model.resource.UriReference
import org.opendc.sdk.model.scheduler.PrefabAllocationPolicySpec
import org.opendc.sdk.model.topology.ClusterSpec
import org.opendc.sdk.model.topology.CpuSpec
import org.opendc.sdk.model.topology.HostSpec
import org.opendc.sdk.model.topology.MemorySpec
import org.opendc.sdk.model.topology.TopologySpec
import org.opendc.sdk.model.workload.TraceWorkloadSpec
import org.opendc.web.dispatcher.ExitOutcome
import org.opendc.web.dispatcher.ExitReason
import org.opendc.web.dispatcher.LaunchRequest
import org.opendc.web.launcher.LaunchManifest
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The local dispatcher is the terminal observer for a subprocess, so the behaviour that matters is
 * how it classifies an ending it did not choose, and that an ending it did choose is not mistaken
 * for one of those.
 */
class LocalDispatcherTest {
    @TempDir
    lateinit var workDir: Path

    private val outcomes = LinkedBlockingQueue<Pair<UUID, ExitOutcome>>()

    private fun dispatcher(mainClass: String = SleepingLauncher::class.java.name): LocalDispatcher =
        LocalDispatcher(
            LocalDispatcherConfig(
                cores = 4,
                memoryMb = 4096.0,
                classpath = System.getProperty("java.class.path"),
                workDir = workDir,
                mainClass = mainClass,
            ),
        ).also { dispatcher -> dispatcher.observe { id, exit -> outcomes.add(id to exit) } }

    private fun request(
        runs: Int = 1,
        source: org.opendc.sdk.model.resource.ResourceReference = UriReference("file:///nowhere/at/all"),
        timeLimitSeconds: Int = 120,
    ) = LaunchRequest(
        executionId = UUID.randomUUID(),
        manifest =
            LaunchManifest(
                scenarios =
                    listOf(
                        ScenarioSpec(
                            topology =
                                TopologySpec(
                                    listOf(
                                        ClusterSpec(
                                            hosts =
                                                listOf(
                                                    HostSpec(
                                                        cpu = CpuSpec(coreCount = 4, coreSpeed = 3.ghz),
                                                        memory = MemorySpec(size = 16.gib),
                                                    ),
                                                ),
                                        ),
                                    ),
                                ),
                            workload = TraceWorkloadSpec(source = source),
                            allocationPolicy = PrefabAllocationPolicySpec(),
                            runs = runs,
                        ),
                    ),
                parallelism = 1,
                results = workDir.resolve("results").toUri().toString(),
            ),
        memoryRequestMb = 768.0,
        heapMb = 256.0,
        timeLimitSeconds = timeLimitSeconds,
    )

    private fun awaitOutcome(): ExitOutcome = (outcomes.poll(60, TimeUnit.SECONDS) ?: error("no outcome arrived")).second

    @Test
    fun `reports a spec the launcher refused as one that no retry can fix`() {
        // runs = 0 fails the scenario's own validation, so the launcher stops before simulating.
        val dispatcher = dispatcher(mainClass = LAUNCHER_MAIN)

        dispatcher.launch(request(runs = 0))

        assertEquals(ExitReason.INVALID_SPEC, awaitOutcome().reason)
    }

    @Test
    fun `treats an input it could not fetch as worth another attempt`() {
        val dispatcher = dispatcher(mainClass = LAUNCHER_MAIN)

        dispatcher.launch(request())

        val exit = awaitOutcome()
        assertEquals(ExitReason.UNKNOWN, exit.reason)
        assertTrue(exit.reason.isResourceShaped, "a transfer that failed may succeed next time")
    }

    // A name reaching a launcher is the server having failed to resolve it, which no retry mends.
    @Test
    fun `refuses a reference the server left as a name`() {
        val dispatcher = dispatcher(mainClass = LAUNCHER_MAIN)

        dispatcher.launch(request(source = NamedReference("bitbrains-small")))

        assertEquals(ExitReason.INVALID_SPEC, awaitOutcome().reason)
    }

    @Test
    fun `calls an overrun a timeout rather than an unexplained death`() {
        val dispatcher = dispatcher()

        dispatcher.launch(request(timeLimitSeconds = 0))

        assertEquals(ExitReason.TIMEOUT, awaitOutcome().reason)
    }

    @Test
    fun `calls a kill it asked for a cancellation, not a failure`() {
        val dispatcher = dispatcher()
        val request = request()

        dispatcher.launch(request)
        dispatcher.cancel(request.executionId)

        assertEquals(ExitReason.CANCELLED, awaitOutcome().reason)
    }

    @Test
    fun `admits nothing larger than what a running execution leaves`() {
        val dispatcher = dispatcher()
        val request = request()

        dispatcher.launch(request)

        assertTrue(dispatcher.admits(cores = 3, memoryMb = 3000.0))
        assertTrue(!dispatcher.admits(cores = 4, memoryMb = 1.0), "one core is already spoken for")
        assertTrue(!dispatcher.admits(cores = 1, memoryMb = 3400.0), "768 MB is already spoken for")
        dispatcher.cancel(request.executionId)
    }

    // Otherwise work larger than the machine waits for room that will never appear.
    @Test
    fun `admits work larger than itself when nothing else is running`() {
        assertTrue(dispatcher().admits(cores = 64, memoryMb = 100_000.0))
    }

    @Test
    fun `knows nothing of an execution from an earlier lifetime`() {
        val dispatcher = dispatcher()

        assertEquals(emptySet<UUID>(), dispatcher.reconcile(listOf(UUID.randomUUID())))
    }

    /** Stands in for a launcher that is still working, so a kill is what ends it. */
    object SleepingLauncher {
        @JvmStatic
        fun main(args: Array<String>) {
            Thread.sleep(120_000)
        }
    }
}
