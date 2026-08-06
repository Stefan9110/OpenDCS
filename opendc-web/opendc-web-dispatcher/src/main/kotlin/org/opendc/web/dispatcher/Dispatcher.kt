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

import org.opendc.web.launcher.LaunchManifest
import java.util.UUID

/**
 * Turns an admitted bag of work into a running process on one execution platform, and reports what
 * became of it.
 *
 * An execution is addressed by the identifier the server minted for it, so a restarted server can
 * cancel and reconcile work it did not start without having stored a platform's own name for it.
 * Terminal facts come from the platform, never from the launcher.
 */
interface Dispatcher {
    /** Identifies this dispatcher in the database and in metrics. Stable across restarts. */
    val name: String

    /** The shape of one execution here, which is what bags are packed for. */
    fun slot(): ExecutionSlot

    /** Whether an execution of this shape could start now. */
    fun admits(
        cores: Int,
        memoryMb: Double,
    ): Boolean

    /** Registers the callback terminal outcomes arrive on. Must be called before [launch]. */
    fun observe(onFinished: (UUID, ExitOutcome) -> Unit)

    fun launch(request: LaunchRequest)

    /**
     * Stops the execution behind [executionId].
     *
     * The platform reports it ending in its own time; the caller settles on that rather than on this
     * returning.
     */
    fun cancel(executionId: UUID)

    /**
     * Which of [executionIds] the platform is still running.
     *
     * Asked once at startup about everything this server believed was live. What does not come back
     * is gone, and its work goes round again; a retry writes the same deterministic output as the
     * attempt it replaces, so nothing has to be cleaned up first.
     */
    fun reconcile(executionIds: List<UUID>): Set<UUID>
}

/** What one execution is given: the concurrency it may use and the memory it must stay under. */
data class ExecutionSlot(
    val cores: Int,
    val memoryMb: Double,
)

/**
 * One bag, ready to run.
 *
 * @property memoryRequestMb What the platform must reserve, including the process itself.
 * @property heapMb What the units inside it need, which is the process heap.
 */
data class LaunchRequest(
    val executionId: UUID,
    val manifest: LaunchManifest,
    val heapMb: Double,
    val memoryRequestMb: Double,
    val timeLimitSeconds: Int,
)

/** How an execution ended, as the platform saw it. */
data class ExitOutcome(
    val reason: ExitReason,
    val exitCode: Int,
    val message: String,
)

/** Why an execution ended, in one vocabulary across every platform. */
enum class ExitReason {
    OK,

    /** The simulation threw. */
    SIMULATION_ERROR,

    /** The spec did not parse or did not validate. */
    INVALID_SPEC,

    /** The kernel ended it for memory. */
    OOM,

    /** It outlived the limit this server set. */
    TIMEOUT,

    /** It outlived a limit the platform set, such as a SLURM partition's. */
    WALLTIME,

    CANCELLED,

    /** Gone, with no verdict. */
    UNKNOWN,

    ;

    /** Whether a different grant, or less work in the bag, could change the outcome. */
    val isResourceShaped: Boolean
        get() = this == OOM || this == TIMEOUT || this == WALLTIME || this == UNKNOWN
}
