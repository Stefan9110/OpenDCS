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

import jakarta.enterprise.context.ApplicationScoped
import org.apache.parquet.hadoop.ParquetFileReader
import org.opendc.trace.spi.TraceFormat
import org.opendc.web.server.model.TraceKind
import org.opendc.web.server.rest.DocumentIssue
import org.opendc.web.server.rest.invalidDocument
import org.opendc.web.server.storage.StoredObjectFile
import org.opendc.web.server.storage.TraceStore
import java.nio.file.Path

/** What a table turned out to hold. */
data class TableFacts(
    val sizeBytes: Long,
    val rowCount: Long,
)

/**
 * Checks that an uploaded table really is the table it was filed as, and reports what it holds.
 *
 * Only the parquet footer is read. It carries the schema and the row count, which is everything
 * needed to tell a real tasks table from a photograph renamed to tasks.parquet, and it costs the
 * same whether the file is ten megabytes or ten gigabytes. Reading rows instead would mean pulling
 * the whole object back out of storage to learn nothing further.
 */
@ApplicationScoped
class TraceIngest(private val store: TraceStore) {
    fun inspect(
        kind: TraceKind,
        table: String,
        key: String,
    ): TableFacts {
        val footer =
            try {
                ParquetFileReader.open(StoredObjectFile(store, key)).use { reader ->
                    Footer(reader.fileMetaData.schema.fields.map { it.name }.toSet(), reader.recordCount)
                }
            } catch (e: Exception) {
                throw invalidDocument(
                    "The $table table could not be read as parquet",
                    listOf(DocumentIssue(table, e.message ?: "not a readable parquet file")),
                )
            }

        // The format describes every column it knows for a table, not the ones a file must carry:
        // real traces leave out what they have nothing to say about, so demanding the full set
        // would refuse most of them. What does give a file away is carrying a column that belongs
        // to one of the kind's *other* tables, which is what swapping two files looks like.
        val own = columnsOf(kind, table)
        if (footer.columns.intersect(own).isEmpty()) {
            throw invalidDocument(
                "That file is not a $table table",
                listOf(DocumentIssue(table, "none of its columns belong to $table")),
            )
        }
        val elsewhere = footer.columns.intersect(siblingColumns(kind, table) - own)
        if (elsewhere.isNotEmpty()) {
            throw invalidDocument(
                "That file is not a $table table",
                listOf(DocumentIssue(table, "it carries the ${elsewhere.sorted().joinToString(", ")} column of another table")),
            )
        }
        return TableFacts(sizeBytes = store.size(key), rowCount = footer.rowCount)
    }

    /** The columns opendc-trace knows for [table], asked of the format so this cannot drift. */
    private fun columnsOf(
        kind: TraceKind,
        table: String,
    ): Set<String> {
        val format = checkNotNull(TraceFormat.byName(kind.name.lowercase())) { "no reader answers to ${kind.name}" }
        return format.getDetails(Path.of("."), table).columns.map { it.name }.toSet()
    }

    private fun siblingColumns(
        kind: TraceKind,
        table: String,
    ): Set<String> = kind.tables.filter { it != table }.flatMap { columnsOf(kind, it) }.toSet()

    private data class Footer(
        val columns: Set<String>,
        val rowCount: Long,
    )
}
