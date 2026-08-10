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

/**
 * Everything one launcher process needs, written by the dispatcher and read at startup. The
 * launcher's whole environment is a single URL naming one of these.
 *
 * Every reference inside a scenario is already a
 * [org.opendc.sdk.model.resource.UriReference]: what a name means is the server's business, and it
 * has answered that question by the time this is written.
 *
 * @property scenarios Fully resolved runs, each narrowed to one repetition by `runs = 1` and its own
 *           `initialSeed`.
 * @property parallelism How many of [scenarios] run at once.
 * @property results Where the finished output tree is copied, as `<results>/<scenario>/seed=<seed>/`.
 * @property telemetry Where progress is reported while the runs are still going. Defaulted to
 *           [TelemetryTarget.None] so a manifest written by hand needs nothing to listen to it.
 */
@Serializable
data class LaunchManifest(
    val scenarios: List<ScenarioSpec>,
    val parallelism: Int,
    val results: String,
    val telemetry: TelemetryTarget = TelemetryTarget.None,
)

/**
 * The codes the launcher chooses to exit with.
 *
 * They start at twenty to stay clear of the codes it does not choose: 1 for an uncaught error, 3
 * under `-XX:+ExitOnOutOfMemoryError`, and 128 plus the signal when a process is killed.
 */
const val EXIT_OK = 0

/** The manifest, or a scenario inside it, did not parse or did not validate. */
const val EXIT_INVALID_SPEC = 20

/** The simulation threw. */
const val EXIT_SIMULATION_ERROR = 21

/** An input could not be read or an output could not be written. */
const val EXIT_TRANSFER_FAILED = 22
