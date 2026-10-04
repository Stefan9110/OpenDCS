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

package org.opendc.web.launcher

import kotlinx.serialization.Serializable
import org.opendc.sdk.model.experiment.ScenarioSpec

/** The one environment variable a launcher reads: where its manifest is. */
const val MANIFEST_URL_VARIABLE = "MANIFEST_URL"

/** The entry point every dispatcher that starts a launcher from its files names. */
const val LAUNCHER_MAIN = "org.opendc.web.launcher.MainKt"

/**
 * Everything one launcher process needs, written by the server and read at startup.
 *
 * The launcher never resolves a name. The server has rewritten every reference in the units to a
 * path relative to the launcher's working directory, and [inputs] says which bytes to put at those
 * paths before anything runs. Nothing consults [inputs] while resolving: it is a staging list, not a
 * resolution table.
 *
 * @property inputs What to fetch before the first unit starts.
 * @property units The runs, each narrowed to one repetition and carrying where its results go.
 * @property parallelism How many units run at once.
 * @property telemetry Where progress is reported while the units are still going. Defaulted to
 *           [TelemetryTarget.None] so a manifest written by hand needs nothing to listen to it.
 */
@Serializable
data class LaunchManifest(
    val inputs: List<StagedInput>,
    val units: List<LaunchUnit>,
    val parallelism: Int,
    val telemetry: TelemetryTarget = TelemetryTarget.None,
)

/**
 * Bytes to put somewhere before the units start.
 *
 * @property path Where, relative to the working directory. It has to stay inside it.
 * @property source Where the bytes are, as a `file:` or `http(s):` URL.
 * @property key The stored object the bytes are, which never changes under one key. A launcher
 *           ignores it; a dispatcher that stages inputs itself caches by it, since [source] is signed
 *           afresh for every manifest.
 */
@Serializable
data class StagedInput(
    val path: String,
    val source: String,
    val key: String,
)

/**
 * One `(scenario, seed)` run.
 *
 * @property scenario The run, with `runs = 1`, its own `initialSeed`, and every reference a staged path.
 * @property outputs Where each file the run writes is published.
 * @property outcome Where the unit's [UnitOutcome] is published, once its outputs are.
 */
@Serializable
data class LaunchUnit(
    val scenario: ScenarioSpec,
    val outputs: List<OutputTarget>,
    val outcome: String,
)

/** Where the output file named [file] is published to. */
@Serializable
data class OutputTarget(
    val file: String,
    val target: String,
)
