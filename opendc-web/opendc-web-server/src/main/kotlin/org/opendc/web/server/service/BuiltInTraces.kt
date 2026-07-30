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

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.transaction.Transactional
import org.opendc.web.server.model.Trace
import org.opendc.web.server.model.TracePart
import org.slf4j.LoggerFactory

/**
 * Puts the traces the deployment ships with into the store, so that a built-in resolves exactly the
 * way an upload does and nothing downstream has to know the difference.
 *
 * The files are bundled as resources under `traces/<slug>/<table>.parquet`, which is the layout the
 * trace's own kind already describes: whichever tables [org.opendc.web.server.model.TraceKind]
 * lists are the ones looked for. A trace whose files are not bundled is left alone rather than
 * guessed at, which is what a deployment that ships none of them looks like.
 */
@ApplicationScoped
class BuiltInTraces(private val store: TraceStore) {
    // Failures are deliberately not swallowed. A trace library that could not be filled is a
    // misconfigured deployment, and finding that out at boot is far better than finding it out
    // when somebody's experiment cannot resolve the trace it names.
    @Transactional
    internal fun seed(
        @Suppress("UNUSED_PARAMETER") @Observes event: StartupEvent,
    ) {
        for (trace in Trace.findBuiltIns()) {
            for (table in trace.kind.tables) {
                seedTable(trace, table)
            }
        }
    }

    private fun seedTable(
        trace: Trace,
        table: String,
    ) {
        val existing = TracePart.find("trace.id = ?1 and tableName = ?2", trace.id, table).firstResult()
        // Re-reads the resource when the row is there but the object is not, which is what a
        // deployment looks like after its bucket has been replaced.
        if (existing != null && store.exists(existing.contentHash)) {
            return
        }

        val resource = "/traces/${trace.slug}/$table.parquet"
        val bundled = javaClass.getResourceAsStream(resource)
        if (bundled == null) {
            LOG.info("Built-in trace {} ships no {} table; leaving it unresolved", trace.slug, table)
            return
        }

        val stored = bundled.use { store.put(it) }
        val part = existing ?: TracePart().also {
            it.trace = trace
            it.tableName = table
        }
        part.contentHash = stored.contentHash
        part.sizeBytes = stored.sizeBytes
        if (existing == null) {
            part.persist()
        }
        LOG.info("Stored built-in trace {} table {} ({} bytes)", trace.slug, table, stored.sizeBytes)
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(BuiltInTraces::class.java)
    }
}