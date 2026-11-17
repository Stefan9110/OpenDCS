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

package org.opendc.web.profiler.report

import org.opendc.web.dispatcher.ExitReason
import org.opendc.web.dispatcher.estimate.UnitEstimate
import org.opendc.web.launcher.PeakMemory
import org.opendc.web.profiler.grid.GridPoint
import org.opendc.web.profiler.probe.ProcSummary
import org.opendc.web.profiler.probe.RecordingReading
import org.opendc.web.profiler.trace.TraceShape

/** Whether the launcher's `/proc` could be read while it ran, which it can on Linux only. */
sealed interface ProcessMemory {
    data class Sampled(val summary: ProcSummary) : ProcessMemory

    data object Unsampled : ProcessMemory
}

/**
 * How a run ended, as the profiler tells it. The dispatcher reads a launcher whose units failed as
 * a clean exit, since the units carry their own outcomes; here such a run is not a finished
 * measurement and gets a reason of its own, so that no figure mistakes it for one.
 */
sealed interface RunEnd {
    /** Every unit published a success and the launcher exited cleanly. */
    data object Finished : RunEnd

    /** The launcher exited cleanly, but [failed] of its units published no success. */
    data class UnitsFailed(val failed: Int) : RunEnd

    /** Killed by the profiler at the point's timeout. */
    data object TimedOut : RunEnd

    /** Exited with a code the dispatcher reads as [reason]. */
    data class Exited(val reason: ExitReason) : RunEnd

    /** The `exit_reason` cell. */
    val label: String
        get() =
            when (this) {
                Finished -> "OK"
                is UnitsFailed -> "UNITS_FAILED"
                TimedOut -> "TIMEOUT"
                is Exited -> reason.name
            }
}

/**
 * Everything one run of one point yielded, as measured from outside the launcher.
 *
 * @property heapMb The `-Xmx` the launcher ran under.
 * @property predicted What the shipped model would have estimated for this point.
 * @property unitSeconds Each unit that finished, in the order it was published.
 * @property peak What the launcher itself reported as it exited.
 */
data class Measurement(
    val point: GridPoint,
    val repeat: Int,
    val shape: TraceShape,
    val activeProcessors: Int,
    val heapMb: Int,
    val predicted: UnitEstimate,
    val exitCode: Int,
    val end: RunEnd,
    val wallSeconds: Double,
    val unitSeconds: List<Double>,
    val memory: ProcessMemory,
    val peak: PeakMemory,
    val recording: RecordingReading,
    val outputBytes: Long,
) {
    /** What a restart checks against to skip this run. */
    val key: String get() = "${point.id}#$repeat"

    /** The file stem of this run's per-point files. */
    val stem: String get() = "${point.id}-$repeat"
}
