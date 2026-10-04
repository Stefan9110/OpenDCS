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
import java.time.Duration
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
 * Storage for the large files a deployment keeps: the tables a trace is made of, and the parquet
 * its experiments produce. Objects are addressed by a key the caller chooses.
 *
 * Keys are derived from what an object belongs to rather than from its contents. Content addressing
 * would deduplicate, but it cannot survive writes that never pass through the server: a signed
 * target has to name a key before anybody has seen the bytes that would hash to it.
 */
interface ObjectStore : AutoCloseable {
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

    /**
     * Every object under [prefix], in order.
     *
     * A run's output is a directory of files whose names the export spec decides, so what an
     * experiment produced can only be answered by asking, not by working out what it should have
     * written and hoping. Objects still being written are not among them.
     */
    fun list(prefix: String): List<String>

    /** Removes an object, along with any upload of it still in flight. Safe to call twice. */
    fun delete(key: String)

    /** Where a browser should send the [sizeBytes] bytes of [key]. */
    fun uploadTarget(
        key: String,
        sizeBytes: Long,
    ): UploadTarget

    /**
     * A URL that reads [key] for [lifetime], for a launcher that holds no credentials of its own.
     *
     * The scheme is what tells a launcher whether the bytes are beside it or somewhere it has to
     * reach, which is the only difference between running next to the store and on another site.
     */
    fun readUrl(
        key: String,
        lifetime: Duration,
    ): String

    /** A URL that writes [key] in one PUT for [lifetime]. Nothing about the content is signed. */
    fun writeUrl(
        key: String,
        lifetime: Duration,
    ): String

    /** Removes every object under [prefix] and the prefix itself. Safe to call twice. */
    fun deletePrefix(prefix: String)

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
 * Where the trace identified by [tracePublicId] keeps its tables.
 *
 * Derived rather than stored, and derived from identity rather than contents: a browser is handed
 * this key before it has sent a byte, which is what lets a trace of any size go straight to object
 * storage without passing through the server.
 */
fun traceKey(tracePublicId: UUID): String = "traces/$tracePublicId"

/** Where one [table] of that trace lives. */
fun traceKey(
    tracePublicId: UUID,
    table: String,
): String = "${traceKey(tracePublicId)}/$table.parquet"

/**
 * Everything the experiment identified by [experimentPublicId] keeps in the store, so deleting it is
 * one prefix to remove.
 */
fun experimentKey(experimentPublicId: UUID): String = "experiments/$experimentPublicId"

/**
 * Where the runs of an experiment publish their parquet, as `<prefix>/<scenario>/seed=<seed>/`. The
 * layout under it is what an archive mirrors, so it matches a local run's output tree.
 */
fun resultKey(experimentPublicId: UUID): String = "${experimentKey(experimentPublicId)}/results"

/** Where one run publishes its files. */
fun runKey(
    experimentPublicId: UUID,
    scenarioIndex: Int,
    seed: Long,
): String = "${resultKey(experimentPublicId)}/$scenarioIndex/seed=$seed"

/** What one execution keeps beside the results: its manifest, its log and its units' outcomes. */
fun executionKey(
    experimentPublicId: UUID,
    executionPublicId: UUID,
): String = "${experimentKey(experimentPublicId)}/executions/$executionPublicId"

fun manifestKey(
    experimentPublicId: UUID,
    executionPublicId: UUID,
): String = "${executionKey(experimentPublicId, executionPublicId)}/manifest.json"

fun logKey(
    experimentPublicId: UUID,
    executionPublicId: UUID,
): String = "${executionKey(experimentPublicId, executionPublicId)}/launcher.log"

/**
 * Where one execution's launcher certifies how one unit ended. Kept under the execution rather than
 * beside the parquet, so an earlier attempt's marker can never be read as this one's.
 */
fun outcomeKey(
    experimentPublicId: UUID,
    executionPublicId: UUID,
    scenarioIndex: Int,
    seed: Long,
): String = "${executionKey(experimentPublicId, executionPublicId)}/units/$scenarioIndex/seed=$seed.json"
