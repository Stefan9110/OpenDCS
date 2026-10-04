/*
 * Copyright (c) 2025 AtLarge Research
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

package org.opendc.sdk.model.telemetry

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Identifies a category of output produced by a simulation run, and the file it is written to.
 *
 * [fileName] lives here so that anything which has to know where a run's output lands ahead of time,
 * such as a scheduler signing an upload target before the run starts, reads it rather than spelling
 * it again.
 */
@Serializable
public enum class OutputFileSpec(public val fileName: String) {
    @SerialName("battery")
    BATTERY("battery.parquet"),

    @SerialName("cluster")
    CLUSTER("cluster.parquet"),

    @SerialName("datacenter")
    DATA_CENTER("dataCenter.parquet"),

    @SerialName("host")
    HOST("host.parquet"),

    @SerialName("powerSource")
    POWER_SOURCE("powerSource.parquet"),

    @SerialName("service")
    SERVICE("service.parquet"),

    @SerialName("task")
    TASK("task.parquet"),
}
