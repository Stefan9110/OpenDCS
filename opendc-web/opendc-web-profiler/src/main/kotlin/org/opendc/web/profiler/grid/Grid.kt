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

import kotlinx.serialization.Serializable
import org.opendc.sdk.model.telemetry.OutputFileSpec

/**
 * What to measure: the traces, each with the host counts to try, and the settings varied one at a
 * time around each trace's base point.
 *
 * @property measureHeapMb The heap a point runs under when it is there to measure need, not to test a grant.
 * @property pointTimeoutMinutes How long one launcher may run before it is killed and recorded as such.
 */
@Serializable
data class Grid(
    val traces: List<GridTrace>,
    val exportIntervalMinutes: List<Int> = listOf(1, 5, 30),
    val tables: List<TableSet> = listOf(TableSet.HOST_ONLY, TableSet.NONE),
    val sampleFractions: List<Double> = listOf(0.5, 0.1),
    val collectors: List<Collector> = listOf(Collector.SERIAL, Collector.G1),
    val parallelism: List<Int> = listOf(4),
    val heapFactors: List<Double> = listOf(1.0, 1.5, 2.0),
    val measureHeapMb: Int = 16384,
    val pointTimeoutMinutes: Int = 30,
    val repeats: Int = 1,
)

/**
 * A trace directory holding `tasks.parquet` and `fragments.parquet`, relative to the traces root.
 *
 * @property efficient Whether to load it through the efficient loader, which needs `num_fragments`.
 */
@Serializable
data class GridTrace(
    val name: String,
    val path: String,
    val hosts: List<Int>,
    val efficient: Boolean = false,
)

/** Which tables a point exports. */
@Serializable
enum class TableSet(val files: List<OutputFileSpec>) {
    ALL(OutputFileSpec.entries),
    HOST_ONLY(listOf(OutputFileSpec.HOST)),
    NONE(emptyList()),
}

/** The garbage collector a point runs under; [DEFAULT] lets the JVM's ergonomics choose, as production does. */
@Serializable
enum class Collector(val flag: String) {
    DEFAULT(""),
    SERIAL("-XX:+UseSerialGC"),
    G1("-XX:+UseG1GC"),
    PARALLEL("-XX:+UseParallelGC"),
}
