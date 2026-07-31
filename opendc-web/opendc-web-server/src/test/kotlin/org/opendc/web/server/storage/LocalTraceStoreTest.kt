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

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LocalTraceStoreTest {
    @TempDir
    lateinit var root: Path

    private val key = traceKey(UUID.randomUUID(), "tasks")

    @Test
    fun `what was stored reads back byte for byte`() {
        val content = ByteArray(200_000) { (it % 251).toByte() }
        val store = store()

        assertEquals(content.size.toLong(), store.put(key, content.inputStream()))
        assertEquals(content.size.toLong(), store.size(key))
        assertArrayEquals(content, store.open(key).use { it.readBytes() })
    }

    // Parquet reads a footer that sits at the end of the file. Without a ranged read, reaching it
    // means pulling everything before it, which for a large trace is the whole object.
    @Test
    fun `a range reads part of an object without reading what comes before it`() {
        val content = ByteArray(5_000) { (it % 251).toByte() }
        val store = store()
        store.put(key, content.inputStream())

        val tail = store.open(key, 4_900, 100).use { it.readBytes() }

        assertArrayEquals(content.copyOfRange(4_900, 5_000), tail)
        assertEquals(100, tail.size)
    }

    @Test
    fun `a range stops at its length rather than running to the end`() {
        val store = store()
        store.put(key, ByteArray(1_000).inputStream())

        assertEquals(10, store.open(key, 0, 10).use { it.readBytes() }.size)
    }

    @Test
    fun `storing over an object replaces it`() {
        val store = store()
        store.put(key, "first".byteInputStream())
        store.put(key, "second attempt".byteInputStream())

        assertEquals("second attempt", store.open(key).use { it.readBytes().decodeToString() })
    }

    @Test
    fun `an object reports its presence and can be removed`() {
        val store = store()
        store.put(key, "transient".byteInputStream())

        assertTrue(store.exists(key))
        store.delete(key)
        assertFalse(store.exists(key))
        // Deleting a trace removes an object per table whether or not each one arrived, so removing
        // what is already gone must not throw.
        store.delete(key)
    }

    @Test
    fun `reading something nobody stored fails rather than returning nothing`() {
        assertThrows<Exception> { store().open(key) }
    }

    // A transfer that breaks partway must leave nothing where a whole object is expected, and no
    // spool beside it either.
    @Test
    fun `nothing is left behind but the object`() {
        val store = store()
        store.put(key, "content".byteInputStream())

        val directory = root.resolve(key).parent
        assertEquals(listOf("tasks.parquet"), Files.list(directory).use { it.toList() }.map { it.fileName.toString() })
    }

    // A directory cannot be written to from a browser, so the local store says so and the server
    // takes the bytes instead. Getting this wrong would hand a development machine a URL to nowhere.
    @Test
    fun `a local store asks for the bytes to come through the server, however large the file`() {
        assertEquals(UploadTarget.ThroughServer, store().uploadTarget(key, 40L * 1024 * 1024 * 1024))
    }

    // There is nothing to assemble, so completing is only a question of whether the request that
    // carried the bytes ever arrived.
    @Test
    fun `an upload is complete once the file is there`() {
        val store = store()

        assertFalse(store.completeUpload(key))
        store.put(key, "arrived".byteInputStream())
        assertTrue(store.completeUpload(key))
    }

    private fun store(): TraceStore = LocalTraceStore(root)
}
