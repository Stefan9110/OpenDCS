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

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.toList

class TraceStorageTest {
    @TempDir
    lateinit var root: Path

    // The key has to be the SHA-256 of the contents and nothing else, because a stored object is
    // matched to a trace by that hash alone. Pinning a known digest catches a change of algorithm
    // or encoding that would silently orphan everything already stored.
    @Test
    fun `an object is keyed by the sha-256 of its contents`() {
        val stored = store().put("hello".byteInputStream())

        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", stored.contentHash)
        assertEquals(5L, stored.sizeBytes)
    }

    @Test
    fun `what was stored reads back byte for byte`() {
        val content = ByteArray(200_000) { (it % 251).toByte() }
        val store = store()

        val stored = store.put(content.inputStream())

        assertEquals(content.size.toLong(), stored.sizeBytes)
        assertArrayEquals(content, store.open(stored.contentHash).use { it.readBytes() })
    }

    // Deduplication is the point of content addressing: two people uploading the same trace must
    // cost one object, and the second upload must not disturb the first.
    @Test
    fun `the same content stored twice occupies one object`() {
        val store = store()

        val first = store.put("identical".byteInputStream())
        val second = store.put("identical".byteInputStream())

        assertEquals(first.contentHash, second.contentHash)
        assertEquals(listOf(first.contentHash), storedNames())
        assertEquals("identical", store.open(first.contentHash).use { it.readBytes().decodeToString() })
    }

    @Test
    fun `different content is kept apart`() {
        val store = store()

        val first = store.put("one".byteInputStream())
        val second = store.put("two".byteInputStream())

        assertTrue(first.contentHash != second.contentHash)
        assertEquals(2, storedNames().size)
    }

    @Test
    fun `an object reports its presence and can be removed`() {
        val store = store()
        val stored = store.put("transient".byteInputStream())

        assertTrue(store.exists(stored.contentHash))
        store.delete(stored.contentHash)
        assertFalse(store.exists(stored.contentHash))
        // Removing what is already gone is how deleting a trace that shares bytes with another
        // behaves once the last reference goes, so it must not throw.
        store.delete(stored.contentHash)
    }

    @Test
    fun `reading something nobody stored fails rather than returning nothing`() {
        assertThrows<Exception> { store().open("0".repeat(64)) }
    }

    // A half-written upload must never appear under a valid hash, so nothing is left behind in the
    // store but the finished object.
    @Test
    fun `no spooled remains are left beside the finished object`() {
        val store = store()
        store.put("content".byteInputStream())

        assertEquals(1, storedNames().size)
        assertTrue(storedNames().none { it.startsWith(".") }, "found a leftover spool file: ${storedNames()}")
    }

    @Test
    fun `the directory is created on first use`() {
        val nested = root.resolve("does/not/exist/yet")

        val stored = LocalTraceStore(nested).put("first".byteInputStream())

        assertTrue(Files.exists(nested.resolve(stored.contentHash)))
    }

    private fun store(): TraceStore = LocalTraceStore(root)

    private fun storedNames(): List<String> = Files.list(root).use { paths -> paths.toList().map { it.fileName.toString() } }
}