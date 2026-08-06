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

import org.opendc.sdk.model.serialization.SdkJson
import org.opendc.web.dispatcher.Dispatcher
import org.opendc.web.dispatcher.ExecutionSlot
import org.opendc.web.dispatcher.ExitOutcome
import org.opendc.web.dispatcher.ExitReason
import org.opendc.web.dispatcher.LaunchRequest
import org.opendc.web.launcher.EXIT_INVALID_SPEC
import org.opendc.web.launcher.EXIT_OK
import org.opendc.web.launcher.EXIT_SIMULATION_ERROR
import org.opendc.web.launcher.EXIT_TRANSFER_FAILED
import org.opendc.web.launcher.LaunchManifest
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

/** The entry point a local execution runs. */
const val LAUNCHER_MAIN = "org.opendc.web.launcher.MainKt"

/**
 * How much of this machine the local dispatcher may use, and where the launcher it starts lives.
 *
 * @property classpath Where [mainClass] and everything it needs is found.
 * @property workDir Where manifests and launcher logs are written.
 */
data class LocalDispatcherConfig(
    val cores: Int,
    val memoryMb: Double,
    val classpath: String,
    val workDir: Path,
    val mainClass: String = LAUNCHER_MAIN,
)

/**
 * Runs bags as subprocesses of this server.
 *
 * The process handle is the terminal observer: an exit code the launcher chose is taken at face
 * value, and anything else is read as the machine having ended the process.
 */
class LocalDispatcher(private val config: LocalDispatcherConfig) : Dispatcher {
    private val running = ConcurrentHashMap<UUID, Running>()
    private val listener = AtomicReference<(UUID, ExitOutcome) -> Unit> { _, _ -> }

    override val name: String get() = NAME

    override fun slot(): ExecutionSlot = ExecutionSlot(config.cores, config.memoryMb)

    /**
     * Work larger than the whole machine is admitted once nothing else is running, since it would
     * otherwise wait for room that will never appear.
     */
    override fun admits(
        cores: Int,
        memoryMb: Double,
    ): Boolean {
        val live = running.values.toList()
        return live.isEmpty() ||
            (live.sumOf { it.cores } + cores <= config.cores && live.sumOf { it.memoryMb } + memoryMb <= config.memoryMb)
    }

    override fun observe(onFinished: (UUID, ExitOutcome) -> Unit) {
        listener.set(onFinished)
    }

    override fun launch(request: LaunchRequest) {
        val directory = config.workDir.resolve(request.executionId.toString())
        Files.createDirectories(directory)
        val manifest = directory.resolve("manifest.json")
        Files.writeString(manifest, SdkJson.json.encodeToString(LaunchManifest.serializer(), request.manifest))

        val builder =
            ProcessBuilder(command(request))
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve("launcher.log").toFile())
        builder.environment()[MANIFEST_URL] = manifest.toUri().toString()

        val process = builder.start()
        running[request.executionId] =
            Running(process, request.manifest.parallelism, request.memoryRequestMb, AtomicReference(Intent.RUN))
        watch(request.executionId, process, request.timeLimitSeconds)
    }

    override fun cancel(executionId: UUID) {
        val execution = running[executionId] ?: return
        execution.intent.set(Intent.CANCEL)
        execution.process.destroyForcibly()
    }

    /**
     * A launcher this server did not start is never adopted.
     *
     * One left over from an earlier lifetime is ended rather than waited for, so the retry that
     * replaces it cannot race a process still writing the same output.
     */
    override fun reconcile(executionIds: List<UUID>): Set<UUID> {
        val live = executionIds.filterTo(mutableSetOf()) { running.containsKey(it) }
        for (executionId in executionIds - live) {
            orphan(executionId)?.destroyForcibly()
        }
        return live
    }

    private fun command(request: LaunchRequest): List<String> =
        listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx${ceil(request.heapMb).toLong()}m",
            "-XX:+ExitOnOutOfMemoryError",
            "-D$EXECUTION_PROPERTY=${request.executionId}",
            "-cp",
            config.classpath,
            config.mainClass,
        )

    private fun watch(
        executionId: UUID,
        process: Process,
        timeLimitSeconds: Int,
    ) {
        Thread
            .ofVirtual()
            .name("opendc-local-$executionId")
            .start {
                if (!process.waitFor(timeLimitSeconds.toLong(), TimeUnit.SECONDS)) {
                    running[executionId]?.intent?.set(Intent.TIMEOUT)
                    process.destroyForcibly()
                    process.waitFor()
                }
                val intent = running.remove(executionId)?.intent?.get() ?: Intent.RUN
                listener.get().invoke(executionId, outcome(process.exitValue(), intent))
            }
    }

    private fun orphan(executionId: UUID): ProcessHandle? =
        ProcessHandle
            .allProcesses()
            .filter { it.info().commandLine().orElse("").contains("$EXECUTION_PROPERTY=$executionId") }
            .findFirst()
            .orElse(null)

    private fun outcome(
        code: Int,
        intent: Intent,
    ): ExitOutcome =
        when (intent) {
            Intent.CANCEL -> ExitOutcome(ExitReason.CANCELLED, code, "cancelled")
            Intent.TIMEOUT -> ExitOutcome(ExitReason.TIMEOUT, code, "exceeded its time limit")
            Intent.RUN -> classify(code)
        }

    private fun classify(code: Int): ExitOutcome =
        when (code) {
            EXIT_OK -> ExitOutcome(ExitReason.OK, code, "")
            EXIT_INVALID_SPEC -> ExitOutcome(ExitReason.INVALID_SPEC, code, "the spec did not validate")
            EXIT_SIMULATION_ERROR -> ExitOutcome(ExitReason.SIMULATION_ERROR, code, "the simulation failed")
            EXIT_TRANSFER_FAILED -> ExitOutcome(ExitReason.UNKNOWN, code, "an input or output could not be transferred")
            JVM_EXIT_ON_OUT_OF_MEMORY, KILLED -> ExitOutcome(ExitReason.OOM, code, "ran out of memory")
            else -> ExitOutcome(ExitReason.UNKNOWN, code, "Unknown failure: exit with code $code")
        }

    private class Running(
        val process: Process,
        val cores: Int,
        val memoryMb: Double,
        val intent: AtomicReference<Intent>,
    )

    /** Why a process ended, as far as this dispatcher is responsible for it. */
    private enum class Intent { RUN, CANCEL, TIMEOUT }

    companion object {
        const val NAME = "local"

        /** The entry point a local execution runs. */
        const val LAUNCHER_MAIN = "org.opendc.web.launcher.MainKt"

        private const val MANIFEST_URL = "MANIFEST_URL"

        /** Identifies a launcher process as belonging to one execution of this server. */
        private const val EXECUTION_PROPERTY = "opendc.execution"

        private const val JVM_EXIT_ON_OUT_OF_MEMORY = 3
        private const val KILLED = 137
    }
}
