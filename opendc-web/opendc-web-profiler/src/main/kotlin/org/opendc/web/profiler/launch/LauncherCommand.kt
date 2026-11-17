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

import org.opendc.web.dispatcher.EXIT_ON_OUT_OF_MEMORY
import org.opendc.web.launcher.LAUNCHER_MAIN
import org.opendc.web.profiler.grid.Collector
import java.nio.file.Path

/** The launcher's jars, as the classpath the JVM expands, and its entry point. */
data class LauncherImage(
    val classpath: String,
    val mainClass: String = LAUNCHER_MAIN,
)

/**
 * The command the local dispatcher would run, with the point's collector, the processor count a
 * pod of that parallelism would see, and the recording added in front of the classpath.
 */
fun launcherCommand(
    image: LauncherImage,
    heapMb: Int,
    collector: Collector,
    activeProcessors: Int,
    recording: List<String>,
): List<String> =
    buildList {
        add(Path.of(System.getProperty("java.home"), "bin", "java").toString())
        add("-Xmx${heapMb}m")
        add(EXIT_ON_OUT_OF_MEMORY)
        if (collector.flag.isNotEmpty()) {
            add(collector.flag)
        }
        add("-XX:ActiveProcessorCount=$activeProcessors")
        addAll(recording)
        add("-cp")
        add(image.classpath)
        add(image.mainClass)
    }
