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
import org.opendc.trace.conv.TABLE_FRAGMENTS
import org.opendc.trace.conv.TABLE_TASKS
import org.opendc.web.launcher.LaunchManifest
import org.opendc.web.launcher.LaunchUnit
import org.opendc.web.launcher.OutputTarget
import org.opendc.web.launcher.PEAK_MEMORY_FILE
import org.opendc.web.launcher.StagedInput
import org.opendc.web.profiler.grid.GridPoint
import org.opendc.web.profiler.grid.STAGED_TRACE
import org.opendc.web.profiler.grid.scenario
import java.nio.file.Files
import java.nio.file.Path

const val MANIFEST_FILE = "manifest.json"

const val RECORDING_FILE = "recording.jfr"

const val RECORDING_REPOSITORY_DIR = "jfr"

const val LAUNCHER_LOG_FILE = "launcher.log"

private const val OUTPUT_DIR = "output"

/**
 * One point's directory as the launcher sees it: its manifest, where each unit publishes its
 * outcome and outputs, where the recording, its chunk repository and the peak-memory report land,
 * and the log its output streams into while it runs.
 */
data class Workspace(
    val dir: Path,
    val manifest: Path,
    val outcomes: List<Path>,
    val outputDir: Path,
    val recording: Path,
    val recordingRepository: Path,
    val peakMemory: Path,
    val log: Path,
)

/**
 * Writes the manifest for [point] into [dir], staging both tables of [traceDir] as `file:` links
 * exactly where the server would put them, and one unit per parallel slot with its own seed. What
 * a killed attempt left in [dir] is removed first, so that none of it is read as this run's.
 */
fun prepareWorkspace(
    point: GridPoint,
    traceDir: Path,
    dir: Path,
): Workspace {
    dir.toFile().deleteRecursively()
    Files.createDirectories(dir)
    val outputDir = dir.resolve(OUTPUT_DIR)
    val seeds = 0 until point.parallelism
    val outcomes = seeds.map { dir.resolve("outcome-$it.json") }
    val manifest =
        LaunchManifest(
            inputs = listOf(TABLE_TASKS, TABLE_FRAGMENTS).map { table -> stagedTable(point, traceDir, table) },
            units = seeds.map { seed -> unitOf(point, seed, outputDir, outcomes[seed]) },
            parallelism = point.parallelism,
        )
    val manifestFile = dir.resolve(MANIFEST_FILE)
    Files.writeString(manifestFile, SdkJson.json.encodeToString(LaunchManifest.serializer(), manifest))
    return Workspace(
        dir = dir,
        manifest = manifestFile,
        outcomes = outcomes,
        outputDir = outputDir,
        recording = dir.resolve(RECORDING_FILE),
        recordingRepository = dir.resolve(RECORDING_REPOSITORY_DIR),
        peakMemory = dir.resolve(PEAK_MEMORY_FILE),
        log = dir.resolve(LAUNCHER_LOG_FILE),
    )
}

private fun stagedTable(
    point: GridPoint,
    traceDir: Path,
    table: String,
): StagedInput =
    StagedInput(
        path = "$STAGED_TRACE/$table.parquet",
        source = traceDir.resolve("$table.parquet").toUri().toString(),
        key = "${point.trace.name}/$table",
    )

private fun unitOf(
    point: GridPoint,
    seed: Int,
    outputDir: Path,
    outcome: Path,
): LaunchUnit =
    LaunchUnit(
        scenario = point.scenario(seed),
        outputs =
            point.tables.files.map { file ->
                OutputTarget(file.fileName, outputDir.resolve("$seed").resolve(file.fileName).toUri().toString())
            },
        outcome = outcome.toUri().toString(),
    )
