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
import jakarta.enterprise.inject.Disposes
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.opendc.web.dispatcher.Dispatcher
import org.opendc.web.dispatcher.ExecutionSlot
import org.opendc.web.dispatcher.TimeCap
import org.opendc.web.dispatcher.kubernetes.KubernetesDispatcher
import org.opendc.web.dispatcher.kubernetes.KubernetesDispatcherConfig
import org.opendc.web.dispatcher.kubernetes.Namespace
import org.opendc.web.dispatcher.kubernetes.PriorityClass
import org.opendc.web.dispatcher.kubernetes.PullPolicy
import org.opendc.web.dispatcher.local.LocalDispatcher
import org.opendc.web.dispatcher.local.LocalDispatcherConfig
import org.opendc.web.dispatcher.slurm.JumpHost
import org.opendc.web.dispatcher.slurm.Partition
import org.opendc.web.dispatcher.slurm.SlurmDispatcher
import org.opendc.web.dispatcher.slurm.SlurmDispatcherConfig
import org.opendc.web.dispatcher.slurm.SshTarget
import org.opendc.web.server.storage.ObjectStoreConfig
import org.opendc.web.server.storage.ObjectStoreKind
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.util.Optional
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Where a deployment runs its simulations. One per deployment.
 *
 * Each kind reads its own section of [DispatcherConfig], so adding one is a section and a branch here.
 */
enum class DispatcherKind {
    /** Subprocesses of this server. What a development machine and a self-hosted install use. */
    LOCAL,

    /** One Job per execution on a Kubernetes cluster. */
    KUBERNETES,

    /** One batch job per execution on a SLURM cluster reached over SSH, staged through its shared filesystem. */
    SLURM,
}

/**
 * Builds the one dispatcher this deployment runs work on, and closes it again on shutdown.
 *
 * Kept past bean removal because a test supplies its own dispatcher instead: dropping this producer
 * would drop the only injection point of [DispatcherConfig] with it, and a deployment's dispatcher
 * settings would then be refused as configuration that maps to nothing.
 */
@Unremovable
@ApplicationScoped
class Dispatch(
    private val config: ExecutionConfig,
    private val dispatchers: DispatcherConfig,
    private val storage: ObjectStoreConfig,
) {
    @Produces
    @Singleton
    fun dispatcher(): Dispatcher {
        val problems = deploymentProblems(config.dispatcher(), storage.kind(), config.telemetryUrl())
        check(problems.isEmpty()) { problems.joinToString("; ") }
        return when (config.dispatcher()) {
            DispatcherKind.LOCAL -> dispatchers.localDispatcher()
            DispatcherKind.KUBERNETES -> KubernetesDispatcher.connect(dispatchers.kubernetes().toConfig())
            DispatcherKind.SLURM -> SlurmDispatcher(dispatchers.slurm().toConfig(dispatchers.launcherDistribution()))
        }
    }

    fun close(
        @Disposes dispatcher: Dispatcher,
    ) {
        dispatcher.close()
    }
}

/** How this deployment's dispatchers are set up. Each kind reads its own section. */
@ConfigMapping(prefix = "opendc.dispatcher")
interface DispatcherConfig {
    /**
     * The `lib` directory of the launcher's distribution, for every dispatcher that starts the
     * launcher from files rather than from an image.
     *
     * The launcher is published as its own program, so this names where that program was installed
     * rather than describing how to assemble one.
     */
    fun launcherLib(): String

    fun local(): Local

    fun kubernetes(): Kubernetes

    fun slurm(): Slurm

    /** What [DispatcherKind.LOCAL] needs to run launchers beside this server. */
    interface Local {
        /** Cores one execution may use. Defaults to every core this machine has. */
        fun cores(): Optional<Int>

        /**
         * Memory the pool may use between all its executions.
         *
         * Defaults to [MEMORY_SHARE] of what the machine has, since the rest of it belongs to this
         * server, to the database and to whoever else is using the machine.
         */
        fun memoryMb(): Optional<Double>

        /** Where manifests and launcher logs are written. */
        @WithDefault("data/executions")
        fun workDir(): String
    }

    /** What [DispatcherKind.KUBERNETES] needs to run launchers as Jobs. */
    interface Kubernetes {
        /** Where Jobs are created. Absent, the namespace this server runs in. */
        fun namespace(): Optional<String>

        /** The launcher image, which has to match this server's version: the manifest is its contract. */
        @WithDefault("ghcr.io/atlarge-research/opendc-launcher:latest")
        fun image(): String

        @WithDefault("if-not-present")
        fun imagePullPolicy(): PullPolicy

        /** Carries only the image pull secrets: the pod mounts no token. */
        @WithDefault("default")
        fun serviceAccount(): String

        fun priorityClass(): Optional<String>

        /** The shape of one execution, which is what bags are packed for. */
        @WithDefault("4")
        fun slotCores(): Int

        @WithDefault("8192")
        fun slotMemoryMb(): Double

        /** How many Jobs may be in flight at once. */
        @WithDefault("16")
        fun maxConcurrent(): Int

        /** Each pod's writable space, for its staged inputs and its output. */
        @WithDefault("20480")
        fun scratchSizeMb(): Int

        /** How long a finished Job lingers if nothing deletes it. Only a safety net. */
        @WithDefault("PT168H")
        fun ttl(): Duration

        /** How long a Job may wait for a node before it is withdrawn and tried again. */
        @WithDefault("PT1H")
        fun pendingTimeout(): Duration
    }

    /** What [DispatcherKind.SLURM] needs to reach a head node and run launchers through it. */
    interface Slurm {
        fun host(): Optional<String>

        @WithDefault("22")
        fun port(): Int

        fun user(): Optional<String>

        /** An unencrypted private key, readable by this server only. */
        fun identityFile(): Optional<String>

        /** Has to vouch for the jump host as well as the head node; nothing else is trusted. */
        @WithDefault("~/.ssh/known_hosts")
        fun knownHosts(): String

        /** `[user@]host[:port]` of a bastion the head node is reached through. */
        fun jumpHost(): Optional<String>

        /** Where launchers, cached traces and executions live, on a filesystem the compute nodes share. */
        @WithDefault("opendc")
        fun remoteRoot(): String

        /** The JRE on the shared filesystem the nodes run the launcher with: Java 21 or newer. */
        @WithDefault("java")
        fun java(): String

        fun partition(): Optional<String>

        /** Site flags every submission carries, such as an account or a QOS. */
        fun sbatchOptions(): Optional<List<String>>

        /** One node's cores and memory, which is what bags are packed for. */
        fun slotCores(): Optional<Int>

        fun slotMemoryMb(): Optional<Double>

        /** The longest a job may run, where the site caps it below the partition's own limit. */
        fun timeCap(): Optional<Duration>

        /** How many jobs may be pending or running at once. */
        @WithDefault("4")
        fun maxJobs(): Int

        @WithDefault("PT1H")
        fun pendingTimeout(): Duration

        /** How often the queue is asked about. Follow the site's etiquette. */
        @WithDefault("PT15S")
        fun pollInterval(): Duration

        /** At least the shared filesystem's attribute cache time, so a fresh exit record is seen. */
        @WithDefault("PT90S")
        fun recordGrace(): Duration

        /** At least opendc.execution.url-lifetime, so nothing live still reads what is removed. */
        @WithDefault("P7D")
        fun cacheRetention(): Duration
    }
}

/** How much of the machine's memory a deployment that says nothing may use for simulations. */
private const val MEMORY_SHARE = 0.5

/** Hosts a launcher somewhere else cannot reach this server on. */
private val LOOPBACK = setOf("localhost", "::1", "[::1]", "0.0.0.0")

/**
 * What stops this deployment from running on [kind]; empty when nothing does.
 *
 * A pod reads its inputs through signed URLs and reports to the telemetry URL, so it can neither read
 * this server's disk nor reach this server's loopback address. Both are certain misconfigurations,
 * and failing at boot says so before anything is submitted. A SLURM job reaches nothing but its
 * shared filesystem, which this server stages into and collects from itself.
 */
fun deploymentProblems(
    kind: DispatcherKind,
    storage: ObjectStoreKind,
    telemetryUrl: String,
): List<String> =
    when (kind) {
        DispatcherKind.LOCAL, DispatcherKind.SLURM -> emptyList()
        DispatcherKind.KUBERNETES ->
            buildList {
                if (storage == ObjectStoreKind.LOCAL) {
                    add("opendc.execution.dispatcher=kubernetes cannot read this server's disk; set opendc.storage.kind=s3")
                }
                val host = URI.create(telemetryUrl).host.orEmpty()
                if (host in LOOPBACK || host.startsWith("127.")) {
                    add("opendc.execution.telemetry-url points at $host, which a pod cannot reach")
                }
            }
    }

fun DispatcherConfig.localDispatcher(): LocalDispatcher =
    LocalDispatcher(
        LocalDispatcherConfig(
            cores = local().cores().orElseGet { Runtime.getRuntime().availableProcessors() },
            memoryMb = local().memoryMb().orElseGet { machineMemoryMb() * MEMORY_SHARE },
            // The wildcard is expanded by the JVM rather than by a shell, so naming the directory
            // puts the launcher's whole distribution on the classpath.
            classpath = "${launcherDistribution().toAbsolutePath()}${File.separator}*",
            workDir = Path.of(local().workDir()),
        ),
    )

fun DispatcherConfig.Kubernetes.toConfig(): KubernetesDispatcherConfig =
    KubernetesDispatcherConfig(
        namespace = namespace().map<Namespace> { Namespace.Named(it) }.orElse(Namespace.OfThisServer),
        image = image(),
        pullPolicy = imagePullPolicy(),
        serviceAccount = serviceAccount(),
        priorityClass = priorityClass().map<PriorityClass> { PriorityClass.Named(it) }.orElse(PriorityClass.ClusterDefault),
        slot = ExecutionSlot(slotCores(), slotMemoryMb(), TimeCap.Unlimited),
        maxConcurrent = maxConcurrent(),
        scratchMb = scratchSizeMb(),
        ttl = ttl(),
        pendingTimeout = pendingTimeout(),
    )

fun DispatcherConfig.Slurm.toConfig(launcherLib: Path): SlurmDispatcherConfig {
    val user = required(user(), "user")
    return SlurmDispatcherConfig(
        ssh =
            SshTarget(
                host = required(host(), "host"),
                port = port(),
                user = user,
                identity = Path.of(home(required(identityFile(), "identity-file"))),
                knownHosts = Path.of(home(knownHosts())),
                jump = jumpHost().map { JumpHost.parse(it, user) }.orElse(JumpHost.Direct),
            ),
        remoteRoot = remoteRoot(),
        java = java(),
        launcherLib = launcherLib,
        partition = partition().map<Partition> { Partition.Named(it) }.orElse(Partition.ClusterDefault),
        sbatchOptions = sbatchOptions().orElse(emptyList()),
        slot =
            ExecutionSlot(
                cores = required(slotCores(), "slot-cores"),
                memoryMb = required(slotMemoryMb(), "slot-memory-mb"),
                timeCap = timeCap().map<TimeCap> { TimeCap.Limited(it.seconds.toInt()) }.orElse(TimeCap.Unlimited),
            ),
        maxJobs = maxJobs(),
        pendingTimeout = pendingTimeout(),
        pollInterval = pollInterval(),
        recordGrace = recordGrace(),
        cacheRetention = cacheRetention(),
    )
}

private fun <T : Any> required(
    value: Optional<T>,
    key: String,
): T = value.orElseThrow { IllegalStateException("opendc.dispatcher.slurm.$key must be set when opendc.execution.dispatcher=slurm") }

/** [path] with a leading `~/` read as this user's home. */
private fun home(path: String): String = if (path.startsWith("~/")) System.getProperty("user.home") + path.removePrefix("~") else path

/** The launcher's `lib` directory, refused at boot unless it holds the launcher. */
private fun DispatcherConfig.launcherDistribution(): Path {
    val lib = Path.of(launcherLib())
    check(lib.isDirectory() && lib.listDirectoryEntries("*.jar").isNotEmpty()) {
        "opendc.dispatcher.launcher-lib is $lib, which holds no launcher; build it with :opendc-web:opendc-web-launcher:installDist"
    }
    return lib
}

private fun machineMemoryMb(): Double =
    (ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean).totalMemorySize / (1024.0 * 1024.0)
