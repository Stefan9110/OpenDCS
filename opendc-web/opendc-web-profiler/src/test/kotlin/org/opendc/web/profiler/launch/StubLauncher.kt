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

package org.opendc.web.profiler.launch

import org.opendc.sdk.model.serialization.SdkJson
import org.opendc.web.launcher.LaunchManifest
import org.opendc.web.launcher.MANIFEST_URL_VARIABLE
import org.opendc.web.launcher.PEAK_MEMORY_FILE
import org.opendc.web.launcher.Payload
import org.opendc.web.launcher.PeakMemory
import org.opendc.web.launcher.UnitOutcome
import org.opendc.web.launcher.publish
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Stands in for the launcher in tests: reads the manifest, holds some heap, publishes every unit's
 * outcome and outputs the way the launcher does, writes the peak report, and exits as told.
 *
 * `STUB_ALLOC_MB`, `STUB_SLEEP_MS`, `STUB_LOG_LINES` and `STUB_EXIT` in the environment shape the
 * run; the first `STUB_FAIL_UNITS` units publish no outcome, as a unit that crashed would not.
 */
object StubLauncher {
    const val UNIT_SECONDS = 0.25

    val PEAK = PeakMemory.Measured(residentMb = 100.0, liveHeapMb = 50.0)

    @JvmStatic
    fun main(args: Array<String>) {
        val manifestUrl = URI(System.getenv(MANIFEST_URL_VARIABLE))
        val manifest = SdkJson.json.decodeFromString(LaunchManifest.serializer(), Files.readString(Path.of(manifestUrl)))
        val held = ArrayList<ByteArray>()
        repeat(setting("STUB_ALLOC_MB", 64)) { held += ByteArray(1 shl 20) }
        System.gc()
        repeat(setting("STUB_LOG_LINES", 0)) { println("stub log line $it") }
        Thread.sleep(setting("STUB_SLEEP_MS", 300).toLong())
        for ((index, unit) in manifest.units.withIndex()) {
            for (target in unit.outputs) {
                publish(Payload(OUTPUT_BYTES.toLong()) { ByteArray(OUTPUT_BYTES).inputStream() }, target.target)
            }
            if (index < setting("STUB_FAIL_UNITS", 0)) {
                continue
            }
            val body = SdkJson.json.encodeToString(UnitOutcome.serializer(), UnitOutcome.Succeeded(UNIT_SECONDS)).encodeToByteArray()
            publish(Payload(body.size.toLong()) { body.inputStream() }, unit.outcome)
        }
        Files.writeString(Path.of(PEAK_MEMORY_FILE), SdkJson.json.encodeToString(PeakMemory.serializer(), PEAK))
        check(held.isNotEmpty())
        exitProcess(setting("STUB_EXIT", 0))
    }

    private const val OUTPUT_BYTES = 16

    private fun setting(
        name: String,
        default: Int,
    ): Int = System.getenv(name)?.toInt() ?: default
}
