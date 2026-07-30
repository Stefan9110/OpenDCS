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

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Disposes
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Optional

/** What storing a file produced: the key it landed under, and how much of it there was. */
data class StoredObject(
    val contentHash: String,
    val sizeBytes: Long,
)

/**
 * Content-addressed storage for the files a trace is made of.
 *
 * Objects are keyed by the SHA-256 of their contents, which buys three things at once: the same
 * file uploaded twice is stored once, a stored object can never disagree with the hash recorded
 * against it, and bytes an experiment has already run against survive the trace row being renamed,
 * unshared or deleted, because nothing about the key depends on who owns it.
 */
interface TraceStore {
    /** Stores [content], returning where it landed. Storing something already held is a no-op. */
    fun put(content: InputStream): StoredObject

    /** Reads back what [put] stored. The caller closes the stream. */
    fun open(contentHash: String): InputStream

    fun exists(contentHash: String): Boolean

    /** Removes an object. Safe to call for one that is already gone. */
    fun delete(contentHash: String)
}

enum class TraceStoreKind {
    /** A directory on disk. What a development machine and the test suite use. */
    LOCAL,

    /** Any S3-compatible object storage, which is what a deployment uses. */
    S3,
}

@ConfigMapping(prefix = "opendc.trace-store")
interface TraceStoreConfig {
    @WithDefault("local")
    fun kind(): TraceStoreKind

    /** Where [TraceStoreKind.LOCAL] keeps its objects. */
    @WithDefault("data/traces")
    fun directory(): String

    fun s3(): S3Config

    interface S3Config {
        fun bucket(): Optional<String>

        /**
         * The endpoint to talk to, absent only for AWS itself. Any S3-compatible provider is named
         * here instead, for example `https://fsn1.your-objectstorage.com`.
         */
        fun endpoint(): Optional<String>

        /** Required by the SDK even where the provider ignores it. */
        @WithDefault("us-east-1")
        fun region(): String

        fun accessKey(): Optional<String>

        fun secretKey(): Optional<String>

        /**
         * Whether a bucket is addressed as `endpoint/bucket` rather than `bucket.endpoint`. The
         * SDK prefers the latter, which most S3-compatible providers do not serve, so this
         * defaults to the form that works everywhere.
         */
        @WithDefault("true")
        fun pathStyle(): Boolean
    }
}

@ApplicationScoped
class TraceStorage(private val config: TraceStoreConfig) {
    @Produces
    @Singleton
    fun traceStore(): TraceStore =
        when (config.kind()) {
            TraceStoreKind.LOCAL -> LocalTraceStore(Path.of(config.directory()))
            TraceStoreKind.S3 -> S3TraceStore(buildClient(config.s3()), required(config.s3().bucket(), "bucket"))
        }

    fun closeStore(
        @Disposes store: TraceStore,
    ) {
        if (store is S3TraceStore) store.close()
    }

    private fun buildClient(s3: TraceStoreConfig.S3Config): S3Client {
        val builder =
            S3Client
                .builder()
                .region(Region.of(s3.region()))
                .forcePathStyle(s3.pathStyle())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                // The SDK attaches flexible-checksum headers to every upload by default, which a
                // number of S3-compatible providers reject outright. AWS is happy either way, so
                // the interoperable setting is simply the right one.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                            required(s3.accessKey(), "access-key"),
                            required(s3.secretKey(), "secret-key"),
                        ),
                    ),
                )
        s3.endpoint().ifPresent { builder.endpointOverride(URI.create(it)) }
        return builder.build()
    }

    private fun required(
        value: Optional<String>,
        name: String,
    ): String =
        value.orElseThrow {
            IllegalStateException("opendc.trace-store.s3.$name must be set when opendc.trace-store.kind=s3")
        }
}

/** A directory of objects, one file per hash. */
class LocalTraceStore(private val root: Path) : TraceStore {
    override fun put(content: InputStream): StoredObject {
        Files.createDirectories(root)
        // Spooled inside the store's own directory so that committing it is a rename on the same
        // filesystem, which cannot half-succeed and leave a truncated object under a valid hash.
        val spool = Files.createTempFile(root, ".incoming-", ".part")
        try {
            val stored = spool.hashFrom(content)
            val target = root.resolve(stored.contentHash)
            if (!Files.exists(target)) {
                Files.move(spool, target, StandardCopyOption.ATOMIC_MOVE)
            }
            return stored
        } finally {
            Files.deleteIfExists(spool)
        }
    }

    override fun open(contentHash: String): InputStream = Files.newInputStream(root.resolve(contentHash))

    override fun exists(contentHash: String): Boolean = Files.exists(root.resolve(contentHash))

    override fun delete(contentHash: String) {
        Files.deleteIfExists(root.resolve(contentHash))
    }
}

/** Objects in an S3-compatible bucket, under a fixed prefix so a shared bucket stays tidy. */
class S3TraceStore(private val client: S3Client, private val bucket: String) : TraceStore {
    override fun put(content: InputStream): StoredObject {
        // The key is the hash, so the whole object has to be read before the request can be
        // addressed. Spooling to a file keeps a large trace off the heap while that happens.
        val spool = Files.createTempFile("opendc-incoming-", ".part")
        try {
            val stored = spool.hashFrom(content)
            if (!exists(stored.contentHash)) {
                client.putObject({ it.bucket(bucket).key(keyOf(stored.contentHash)) }, spool)
            }
            return stored
        } finally {
            Files.deleteIfExists(spool)
        }
    }

    override fun open(contentHash: String): InputStream = client.getObject { it.bucket(bucket).key(keyOf(contentHash)) }

    override fun exists(contentHash: String): Boolean =
        try {
            client.headObject { it.bucket(bucket).key(keyOf(contentHash)) }
            true
        } catch (e: NoSuchKeyException) {
            false
        }

    override fun delete(contentHash: String) {
        client.deleteObject { it.bucket(bucket).key(keyOf(contentHash)) }
    }

    fun close() {
        client.close()
    }

    private fun keyOf(contentHash: String): String = "$KEY_PREFIX$contentHash"

    private companion object {
        const val KEY_PREFIX = "traces/"
    }
}

/** Writes [content] into this path, reporting the hash and size of what went through. */
private fun Path.hashFrom(content: InputStream): StoredObject {
    val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L
    Files.newOutputStream(this).use { out ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = content.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            out.write(buffer, 0, read)
            size += read
        }
    }
    return StoredObject(digest.digest().joinToString("") { "%02x".format(it) }, size)
}