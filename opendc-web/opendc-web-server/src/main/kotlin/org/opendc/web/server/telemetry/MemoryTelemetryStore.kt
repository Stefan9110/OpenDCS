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

package org.opendc.web.server.telemetry

import org.opendc.web.launcher.MetricSeries
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Samples held in this server's own memory. What a development machine and the test suite use, and
 * what a single-server deployment can keep using.
 *
 * Nothing here survives a restart, which is the same guarantee the deployment backend gives with
 * persistence switched off. A server with more than one replica needs the shared one, or a browser
 * polling a chart would see it flicker between whichever replica answered.
 */
class MemoryTelemetryStore(private val ttl: Duration) : TelemetryStore {
    private val reported = ConcurrentHashMap<RunKey, Reported>()

    override fun write(
        key: RunKey,
        series: List<MetricSeries>,
    ) {
        expire()
        reported[key] = Reported(series, Instant.now().plus(ttl))
    }

    override fun read(keys: List<RunKey>): Map<RunKey, List<MetricSeries>> {
        expire()
        return keys.mapNotNull { key -> reported[key]?.let { key to it.series } }.toMap()
    }

    override fun forget(keys: List<RunKey>) {
        keys.forEach { reported.remove(it) }
    }

    /**
     * Removes what a store with expiry would have removed on its own.
     *
     * Swept on every call rather than on a timer: the map is only ever as large as the runs that have
     * reported recently, and a sweep nobody is waiting on is a thread to shut down cleanly at exit.
     */
    private fun expire() {
        val now = Instant.now()
        reported.entries.removeIf { it.value.until < now }
    }

    private data class Reported(
        val series: List<MetricSeries>,
        val until: Instant,
    )
}
