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

package org.opendc.web.profiler.trace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opendc.web.profiler.Fixtures
import java.nio.file.Files
import java.nio.file.Path

/** The features of a point come from footers alone, and a broken trace has to say which table broke. */
class TraceInventoryTest {
    @TempDir
    lateinit var dir: Path

    private fun readable(): TraceShape = (inspectTrace(Fixtures.BITBRAINS_SMALL) as TraceReading.Readable).shape

    @Test
    fun `counts the rows of both tables`() {
        val shape = readable()

        assertEquals(50L, shape.tasks)
        assertEquals(276_979L, shape.fragments)
    }

    @Test
    fun `reads a span from the tasks statistics`() {
        assertTrue(readable().spanSeconds > 0.0)
    }

    @Test
    fun `names the table a directory lacks`() {
        Files.copy(Fixtures.BITBRAINS_SMALL.resolve("tasks.parquet"), dir.resolve("tasks.parquet"))

        assertEquals(TraceReading.Missing("fragments"), inspectTrace(dir))
    }

    @Test
    fun `reports a file that is not parquet as unreadable`() {
        Files.writeString(dir.resolve("tasks.parquet"), "not parquet")
        Files.writeString(dir.resolve("fragments.parquet"), "not parquet")

        val reading = inspectTrace(dir)

        assertTrue(reading is TraceReading.Unreadable, "$reading")
        assertEquals("tasks", (reading as TraceReading.Unreadable).table)
    }
}
