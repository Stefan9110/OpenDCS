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

package org.opendc.web.server.storage

import java.io.InputStream
import java.util.UUID

/** A stretch of a file, and the signed target that takes exactly that stretch. */
data class PartTarget(
    val url: String,
    val offset: Long,
    val length: Long,
)

/**
 * Where the browser should send the bytes of one table.
 *
 * Object storage can be written to from a browser, which is the whole point: a trace of tens of
 * gigabytes never crosses this server. A directory on a development machine cannot, so it says so
 * and the server takes the bytes itself.
 */
sealed interface UploadTarget {
    /**
     * The browser writes to storage itself, sending [parts] which may go at once.
     *
     * A single connection to object storage settles at a few megabytes a second however much the
     * link can carry, so for a large file it is the number of parts rather than the link that
     * decides how long the upload takes. Anything small enough to go in one piece has one part.
     */
    data class Direct(val parts: List<PartTarget>) : UploadTarget

    data object ThroughServer : UploadTarget
}

/**
 * Storage for the files a trace is made of, addressed by a key the caller chooses.
 *
 * Keys are derived from the trace an object belongs to rather than from its contents. Content
 * addressing would deduplicate, but it cannot survive uploads that never pass through the server:
 * a signed target has to name a key before anybody has seen the bytes that would hash to it.
 */
interface TraceStore : AutoCloseable {
    /** Stores [content] under [key], returning how much of it there was. */
    fun put(
        key: String,
        content: InputStream,
    ): Long

    /** Reads an object whole. The caller closes the stream. */
    fun open(key: String): InputStream

    /**
     * Reads [length] bytes from [offset]. This is what makes a remote object readable without
     * fetching it: a parquet footer sits at the end of the file, and finding it in a stream that
     * only goes forwards means reading everything before it.
     */
    fun open(
        key: String,
        offset: Long,
        length: Long,
    ): InputStream

    fun size(key: String): Long

    fun exists(key: String): Boolean

    /** Removes an object, along with any upload of it still in flight. Safe to call twice. */
    fun delete(key: String)

    /** Where a browser should send the [sizeBytes] bytes of [key]. */
    fun uploadTarget(
        key: String,
        sizeBytes: Long,
    ): UploadTarget

    /**
     * Assembles an upload that was sent in parts, and says whether [key] is now there and whole.
     *
     * Anything sent in one piece is an object already and there is nothing to assemble. An upload
     * whose parts did not all arrive is assembled into nothing at all: storage will happily join up
     * whatever it was given, and a trace missing a stretch out of its middle is far worse than one
     * that is plainly absent.
     */
    fun completeUpload(key: String): Boolean
}

/**
 * Where the file holding [table] of the trace identified by [tracePublicId] lives in the store.
 *
 * Derived rather than stored, and derived from identity rather than contents: a browser is handed
 * this key before it has sent a byte, which is what lets a trace of any size go straight to object
 * storage without passing through the server.
 */
fun traceKey(
    tracePublicId: UUID,
    table: String,
): String = "traces/$tracePublicId/$table.parquet"
