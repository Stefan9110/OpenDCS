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

package org.opendc.web.server.execution

import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import org.opendc.web.dispatcher.DispatchPolicy
import org.opendc.web.dispatcher.estimate.EstimatorCoefficients

/** How work is estimated, shaped and escalated, and where it runs. */
@ConfigMapping(prefix = "opendc.execution")
interface ExecutionConfig {
    /** The platform this deployment dispatches to. **/
    fun dispatcher(): DispatcherKind

    fun estimator(): EstimatorSettings

    fun packing(): PackingSettings

    fun retry(): RetrySettings

    /**
     * The terms of the resource model.
     *
     * The defaults are fitted to launcher runs over the sample traces, from fifty tasks to ten
     * million fragments, on one ordinary machine. What differs between machines is a constant, which
     * is what the two multipliers are for: raise them where estimates come out short, lower them on
     * hardware that beats the reference.
     */
    interface EstimatorSettings {
        /** What one run holds however small its workload: the engine, and the writers of its output. */
        @WithDefault("80.0")
        fun baseMemoryMb(): Double

        /** Per simulated host. Small: hosts cost time rather than memory. */
        @WithDefault("0.03")
        fun memoryPerHostMb(): Double

        /** Per million fragments, each of which becomes an object on the heap. */
        @WithDefault("80.0")
        fun memoryPerMillionFragmentsMb(): Double

        /** Per million tasks. The heaviest term: a task carries its own fragment list and metadata. */
        @WithDefault("2500.0")
        fun memoryPerMillionTasksMb(): Double

        /** Starting and finishing one run, the JVM included. */
        @WithDefault("2.0")
        fun baseSeconds(): Double

        /** Per million fragments read off disk and turned into objects, before anything is simulated. */
        @WithDefault("1.2")
        fun loadSecondsPerMillionFragments(): Double

        /** Per million fragments actually stepped through by the engine. */
        @WithDefault("1.6")
        fun simulateSecondsPerMillionFragments(): Double

        /** Per million tasks admitted, placed and accounted for. Dominates a task-heavy trace. */
        @WithDefault("1800.0")
        fun secondsPerMillionTasks(): Double

        /** What one host adds to the cost of writing the host table, at the default export interval. */
        @WithDefault("0.005")
        fun exportCostPerHost(): Double

        /** What injecting failures adds, as a fraction. */
        @WithDefault("0.25")
        fun failureOverhead(): Double

        /** What checkpointing adds, as a fraction. */
        @WithDefault("0.15")
        fun checkpointOverhead(): Double

        /** What this deployment's cores do to the model's seconds. */
        @WithDefault("1.0")
        fun runtimeMultiplier(): Double

        /** What this deployment's runtime does to the model's megabytes. */
        @WithDefault("1.0")
        fun memoryMultiplier(): Double
    }

    interface PackingSettings {
        /**
         * What a launcher process costs beyond the heap its runs need: the JVM itself, its metaspace
         * and its garbage collector. Paid once however many runs share the process.
         */
        @WithDefault("256.0")
        fun jvmBaselineMb(): Double

        /**
         * What a launcher costs before its first run begins: starting a JVM, loading the classes a
         * simulation needs and fetching its inputs. Added to every bag's time limit, because it is
         * paid once and does not scale with the work inside.
         */
        @WithDefault("30.0")
        fun startupSeconds(): Double

        /** How far past its estimate the work in a bag may run before it is killed. */
        @WithDefault("3.0")
        fun timeSafetyFactor(): Double
    }

    interface RetrySettings {
        @WithDefault("3")
        fun maxAttempts(): Int

        @WithDefault("2.0")
        fun growthFactor(): Double

        @WithDefault("32768.0")
        fun maxMemoryRequestMb(): Double
    }
}

fun ExecutionConfig.EstimatorSettings.toCoefficients(): EstimatorCoefficients =
    EstimatorCoefficients(
        baseMemoryMb = baseMemoryMb(),
        memoryPerHostMb = memoryPerHostMb(),
        memoryPerMillionFragmentsMb = memoryPerMillionFragmentsMb(),
        memoryPerMillionTasksMb = memoryPerMillionTasksMb(),
        baseSeconds = baseSeconds(),
        loadSecondsPerMillionFragments = loadSecondsPerMillionFragments(),
        simulateSecondsPerMillionFragments = simulateSecondsPerMillionFragments(),
        secondsPerMillionTasks = secondsPerMillionTasks(),
        exportCostPerHost = exportCostPerHost(),
        failureOverhead = failureOverhead(),
        checkpointOverhead = checkpointOverhead(),
    )

fun ExecutionConfig.toPolicy(): DispatchPolicy =
    DispatchPolicy(
        jvmBaselineMb = packing().jvmBaselineMb(),
        startupSeconds = packing().startupSeconds(),
        timeSafetyFactor = packing().timeSafetyFactor(),
        maxAttempts = retry().maxAttempts(),
        growthFactor = retry().growthFactor(),
        maxMemoryRequestMb = retry().maxMemoryRequestMb(),
    )
