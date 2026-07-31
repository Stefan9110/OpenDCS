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

import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.MultipartUpload
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.Part
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest
import java.io.InputStream
import java.nio.file.Files
import java.time.Duration

/**
 * How much of a file one connection is asked to carry.
 *
 * Measured against a third-party provider, one connection settles at a few megabytes a second and
 * eight of them at eight times that, with no sign of the link itself being the limit. So the size
 * is picked to leave a large file with parts to spare rather than to be as large as the protocol
 * permits: a file sent as one part is a file sent at the speed of one connection.
 */
private const val TARGET_PART_SIZE = 32L * 1024 * 1024

/** All S3 will accept in one upload. A file too large for that many gets larger parts instead. */
private const val MAXIMUM_PARTS = 10_000L

/**
 * The stretches a file of [sizeBytes] is cut into, in order and covering all of it.
 *
 * Every part but the last is a full [TARGET_PART_SIZE], which is what makes an incomplete set
 * recognisable later: parts arrive numbered, so one that is missing shows up either as a gap in the
 * numbering or as a part that is short where the plan says it cannot be.
 */
internal fun planParts(sizeBytes: Long): List<LongRange> {
    val partSize = maxOf(TARGET_PART_SIZE, ceilDiv(sizeBytes, MAXIMUM_PARTS))
    val count = maxOf(1L, ceilDiv(sizeBytes, partSize))
    return (0 until count).map { index ->
        val offset = index * partSize
        offset..<minOf(offset + partSize, sizeBytes)
    }
}

private fun ceilDiv(
    value: Long,
    by: Long,
): Long = (value + by - 1) / by

/** Objects in an S3-compatible bucket, which is what a deployment uses. */
class S3TraceStore(
    private val client: S3Client,
    private val presigner: S3Presigner,
    private val bucket: String,
    private val uploadWindow: Duration,
) : TraceStore {
    override fun put(
        key: String,
        content: InputStream,
    ): Long {
        // Spooled because the SDK needs a content length up front, which a stream cannot give.
        val spool = Files.createTempFile("opendc-incoming-", ".part")
        try {
            val size = Files.newOutputStream(spool).use { content.copyTo(it) }
            client.putObject({ it.bucket(bucket).key(key) }, spool)
            return size
        } finally {
            Files.deleteIfExists(spool)
        }
    }

    override fun open(key: String): InputStream = client.getObject { it.bucket(bucket).key(key) }

    override fun open(
        key: String,
        offset: Long,
        length: Long,
    ): InputStream = client.getObject { it.bucket(bucket).key(key).range("bytes=$offset-${offset + length - 1}") }

    override fun size(key: String): Long = client.headObject { it.bucket(bucket).key(key) }.contentLength()

    override fun exists(key: String): Boolean =
        try {
            client.headObject { it.bucket(bucket).key(key) }
            true
        } catch (e: NoSuchKeyException) {
            false
        }

    override fun delete(key: String) {
        client.deleteObject { it.bucket(bucket).key(key) }
        // Parts of an upload nobody finished are stored, and charged for, without ever being an
        // object, so deleting the object is not on its own enough to give the space back.
        for (upload in uploadsOf(key)) {
            client.abortMultipartUpload { it.bucket(bucket).key(key).uploadId(upload.uploadId()) }
        }
    }

    override fun uploadTarget(
        key: String,
        sizeBytes: Long,
    ): UploadTarget {
        val slices = planParts(sizeBytes)
        // One part is a whole file, and a plain signed PUT carries it without the bucket having to
        // hold an upload open. Nothing is left behind if the browser never sends it.
        if (slices.size == 1) {
            val signed =
                presigner.presignPutObject(
                    PutObjectPresignRequest
                        .builder()
                        .signatureDuration(uploadWindow)
                        .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(key).build())
                        .build(),
                )
            return UploadTarget.Direct(listOf(PartTarget(signed.url().toString(), 0, sizeBytes)))
        }

        val uploadId = client.createMultipartUpload { it.bucket(bucket).key(key) }.uploadId()
        return UploadTarget.Direct(
            slices.mapIndexed { index, slice ->
                val signed =
                    presigner.presignUploadPart(
                        UploadPartPresignRequest
                            .builder()
                            .signatureDuration(uploadWindow)
                            .uploadPartRequest(
                                UploadPartRequest
                                    .builder()
                                    .bucket(bucket)
                                    .key(key)
                                    // Part numbers start at one, and are what storage orders the
                                    // assembled object by.
                                    .partNumber(index + 1)
                                    .uploadId(uploadId)
                                    .build(),
                            ).build(),
                    )
                PartTarget(signed.url().toString(), slice.first, slice.last - slice.first + 1)
            },
        )
    }

    override fun completeUpload(key: String): Boolean {
        val uploadId = uploadsOf(key).maxByOrNull { it.initiated() }?.uploadId() ?: return exists(key)
        val parts = client.listPartsPaginator { it.bucket(bucket).key(key).uploadId(uploadId) }.parts().toList()
        if (!arrivedWhole(parts)) {
            // Given back rather than left open, so a fresh attempt starts clean and the bucket
            // stops charging for bytes that are never going to become an object.
            client.abortMultipartUpload { it.bucket(bucket).key(key).uploadId(uploadId) }
            return false
        }
        client.completeMultipartUpload { request ->
            request
                .bucket(bucket)
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(
                    CompletedMultipartUpload
                        .builder()
                        .parts(parts.map { CompletedPart.builder().partNumber(it.partNumber()).eTag(it.eTag()).build() })
                        .build(),
                )
        }
        return true
    }

    override fun close() {
        client.close()
        presigner.close()
    }

    /**
     * Whether these are all the parts of a file, judged against how [planParts] cut it up: numbered
     * from one without a gap, and every part but the last the same full size.
     *
     * Storage assembles whatever it is handed without comment, so a set with a part missing out of
     * the middle would otherwise become an object very nearly the size of the trace, holding a
     * parquet footer that points at the wrong places. A set missing its *last* parts is not caught
     * here and does not need to be: that leaves the file without the footer parquet reads it by, so
     * it is refused when the trace is checked over.
     */
    private fun arrivedWhole(parts: List<Part>): Boolean {
        if (parts.isEmpty()) return false
        val ordered = parts.sortedBy { it.partNumber() }
        if (ordered.map { it.partNumber() } != List(ordered.size) { it + 1 }) return false
        val full = ordered.first().size()
        return ordered.dropLast(1).all { it.size() == full } && ordered.last().size() <= full
    }

    /**
     * The uploads of [key] the bucket is still holding open.
     *
     * Asked of the bucket rather than remembered here: an upload id means nothing except to the
     * bucket that issued it, and one read back cannot have drifted from what the bucket really has.
     */
    private fun uploadsOf(key: String): List<MultipartUpload> =
        client
            .listMultipartUploads { it.bucket(bucket).prefix(key) }
            .uploads()
            .filter { it.key() == key }
}
