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

import com.sun.management.OperatingSystemMXBean
import io.quarkus.arc.Unremovable
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithDefault
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.opendc.web.dispatcher.Dispatcher
import org.opendc.web.dispatcher.local.LocalDispatcher
import org.opendc.web.dispatcher.local.LocalDispatcherConfig
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.util.Optional

/**
 * Where a deployment runs its simulations.
 *
 * Each kind reads its own configuration, so adding one is a settings interface and a branch here.
 */
enum class DispatcherKind {
    /** Subprocesses of this server. What a development machine and a self-hosted install use. */
    LOCAL,
}

/**
 * Builds the one dispatcher this deployment runs work on.
 *
 * Kept past bean removal because a test supplies its own dispatcher instead: dropping this producer
 * would drop the only injection point of [LocalDispatchConfig] with it, and a deployment's local
 * settings would then be refused as configuration that maps to nothing.
 */
@Unremovable
@ApplicationScoped
class Dispatch(
    private val config: ExecutionConfig,
    private val local: LocalDispatchConfig,
) {
    @Produces
    @Singleton
    fun dispatcher(): Dispatcher =
        when (config.dispatcher()) {
            DispatcherKind.LOCAL -> local.toDispatcher()
        }
}

/** What [DispatcherKind.LOCAL] needs to run launchers beside this server. */
@ConfigMapping(prefix = "opendc.dispatcher.local")
interface LocalDispatchConfig {
    /** Cores one execution may use. Defaults to every core this machine has. */
    fun cores(): Optional<Int>

    /**
     * Memory the pool may use between all its executions.
     *
     * Defaults to [MEMORY_SHARE] of what the machine has, since the rest of it belongs to this
     * server, to the database and to whoever else is using the machine.
     */
    fun memoryMb(): Optional<Double>

    /**
     * The `lib` directory of the launcher's distribution.
     *
     * The launcher is published as its own program, so this names where that program was installed
     * rather than describing how to assemble one.
     */
    fun launcherLib(): String

    /** Where manifests and launcher logs are written. */
    @WithDefault("data/executions")
    fun workDir(): String
}

/** How much of the machine's memory a deployment that says nothing may use for simulations. */
private const val MEMORY_SHARE = 0.5

fun LocalDispatchConfig.toDispatcher(): LocalDispatcher =
    LocalDispatcher(
        LocalDispatcherConfig(
            cores = cores().orElseGet { Runtime.getRuntime().availableProcessors() },
            memoryMb = memoryMb().orElseGet { machineMemoryMb() * MEMORY_SHARE },
            // The wildcard is expanded by the JVM rather than by a shell, so naming the directory
            // puts the launcher's whole distribution on the classpath.
            classpath = "${Path.of(launcherLib()).toAbsolutePath()}${File.separator}*",
            workDir = Path.of(workDir()),
        ),
    )

private fun machineMemoryMb(): Double =
    (ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean).totalMemorySize / (1024.0 * 1024.0)
