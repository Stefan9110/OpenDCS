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

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.transaction.Transactional
import org.opendc.web.server.model.Trace
import org.opendc.web.server.model.TraceGrant
import org.opendc.web.server.model.TraceOrigin
import org.opendc.web.server.model.TracePart
import org.opendc.web.server.storage.ObjectStore
import org.opendc.web.server.storage.traceKey
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Removes traces, and removes the ones nobody ever finished uploading.
 *
 * Registering a trace, sending its tables and completing are three separate requests, so a browser
 * that closes in the middle leaves a row and possibly some objects behind that nothing will ever
 * finish. They are invisible, since the library only shows a trace once every table has arrived,
 * and their name can be claimed again, so nothing is blocked by them. What they do occupy is
 * storage, and only a sweep like this one gives it back.
 */
@ApplicationScoped
class TraceDisposal(private val store: ObjectStore) {
    /** Removes a trace and everything belonging to it, in the store as well as the database. */
    fun discard(trace: Trace) {
        for (grant in TraceGrant.findByTrace(trace.id)) {
            grant.delete()
        }
        for (part in TracePart.findByTrace(trace.id)) {
            part.delete()
        }
        for (table in trace.kind.tables) {
            store.delete(traceKey(trace.publicId, table))
        }
        trace.delete()
        Trace.flush()
    }

    @Scheduled(every = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    fun sweepAbandoned() {
        val cutoff = Instant.now().minus(ABANDONED_AFTER)
        val stale = Trace.list("origin = ?1 and updatedAt < ?2", TraceOrigin.UPLOADED, cutoff)
        for (trace in stale.filter { it.isIncomplete() }) {
            LOG.info("Removing {}, registered {} ago and never finished", trace.slug, ABANDONED_AFTER)
            discard(trace)
        }
    }

    private fun Trace.isIncomplete(): Boolean = !TracePart.findByTrace(id).map { it.tableName }.containsAll(kind.tables)

    private companion object {
        val ABANDONED_AFTER: Duration = Duration.ofHours(24)
        val LOG = LoggerFactory.getLogger(TraceDisposal::class.java)
    }
}
