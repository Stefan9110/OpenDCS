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

package org.opendc.web.profiler.trace

import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData
import org.opendc.trace.conv.TABLE_FRAGMENTS
import org.opendc.trace.conv.TABLE_TASKS
import org.opendc.trace.conv.TASK_DURATION
import org.opendc.trace.conv.TASK_SUBMISSION_TIME
import org.opendc.trace.parquet.LocalInputFile
import java.nio.file.Files
import java.nio.file.Path

private const val MILLIS_PER_SECOND = 1000.0

/** What a trace asks of a run: its rows, and the simulated time it spans. */
data class TraceShape(
    val tasks: Long,
    val fragments: Long,
    val spanSeconds: Double,
)

/** What inspecting a trace directory found. */
sealed interface TraceReading {
    data class Readable(val shape: TraceShape) : TraceReading

    data class Missing(val table: String) : TraceReading

    data class Unreadable(val table: String, val message: String) : TraceReading
}

/**
 * Reads the two tables' footers: row counts for both, and for the tasks table the span from its
 * column statistics, as the last submission plus the longest duration, less the first submission.
 */
fun inspectTrace(dir: Path): TraceReading {
    for (table in listOf(TABLE_TASKS, TABLE_FRAGMENTS)) {
        if (!Files.exists(tableFile(dir, table))) {
            return TraceReading.Missing(table)
        }
    }
    val tasks = readFooter(dir, TABLE_TASKS)
    if (tasks is Footer.Failed) {
        return TraceReading.Unreadable(tasks.table, tasks.message)
    }
    val fragments = readFooter(dir, TABLE_FRAGMENTS)
    if (fragments is Footer.Failed) {
        return TraceReading.Unreadable(fragments.table, fragments.message)
    }
    check(tasks is Footer.Read && fragments is Footer.Read)
    return TraceReading.Readable(TraceShape(tasks.rows, fragments.rows, tasks.spanSeconds))
}

private sealed interface Footer {
    data class Read(val rows: Long, val spanSeconds: Double) : Footer

    data class Failed(val table: String, val message: String) : Footer
}

private fun tableFile(
    dir: Path,
    table: String,
): Path = dir.resolve("$table.parquet")

private fun readFooter(
    dir: Path,
    table: String,
): Footer =
    try {
        ParquetFileReader.open(LocalInputFile(tableFile(dir, table))).use { reader ->
            val columns = reader.footer.blocks.flatMap { it.columns }
            Footer.Read(reader.recordCount, spanSeconds(columns))
        }
    } catch (e: Exception) {
        Footer.Failed(table, e.message ?: e.javaClass.simpleName)
    }

/** Zero when the footer carries no statistics for the two columns, as for a fragments table. */
private fun spanSeconds(columns: List<ColumnChunkMetaData>): Double {
    val submissions = columns.filter { it.path.toDotString() == TASK_SUBMISSION_TIME && it.hasValues() }
    val durations = columns.filter { it.path.toDotString() == TASK_DURATION && it.hasValues() }
    if (submissions.isEmpty() || durations.isEmpty()) {
        return 0.0
    }
    val firstSubmission = submissions.minOf { it.statistics.genericGetMin().toLong() }
    val lastSubmission = submissions.maxOf { it.statistics.genericGetMax().toLong() }
    val longestDuration = durations.maxOf { it.statistics.genericGetMax().toLong() }
    return (lastSubmission + longestDuration - firstSubmission) / MILLIS_PER_SECOND
}

private fun ColumnChunkMetaData.hasValues(): Boolean = statistics != null && statistics.hasNonNullValue()

private fun Comparable<*>.toLong(): Long = (this as Number).toLong()
