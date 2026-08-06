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

import org.opendc.sdk.model.resource.NamedReference
import org.opendc.sdk.model.resource.ProvisionedResource
import org.opendc.sdk.model.resource.ResourceProvisioner
import org.opendc.sdk.model.resource.UriReference
import org.opendc.sdk.model.serialization.SdkJson
import org.opendc.sdk.runner.OpenDC
import org.opendc.sdk.runner.SimulationReport
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.system.exitProcess

/** Names the manifest that says everything else. */
private const val MANIFEST_URL = "MANIFEST_URL"

/** What the output tree under the working directory is called. It never leaves this process. */
private const val RUN = "run"

/** A failure the launcher recognises well enough to give the dispatcher a code for. */
class LaunchFailure(
    val exitCode: Int,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

fun main(): Unit = exitProcess(launch())

private fun launch(): Int {
    val workDir = Files.createTempDirectory("opendc-launcher")
    return try {
        run(readManifest(workDir), workDir)
        EXIT_OK
    } catch (e: Exception) {
        e.printStackTrace()
        exitCodeOf(e)
    } finally {
        workDir.toFile().deleteRecursively()
    }
}

private fun readManifest(workDir: Path): LaunchManifest {
    val url = System.getenv(MANIFEST_URL) ?: throw LaunchFailure(EXIT_INVALID_SPEC, "$MANIFEST_URL is not set")
    val file = fetch(url, workDir.resolve("manifest.json"))
    return try {
        SdkJson.json.decodeFromString<LaunchManifest>(Files.readString(file))
    } catch (e: Exception) {
        throw LaunchFailure(EXIT_INVALID_SPEC, "The manifest at $url could not be read", e)
    }
}

/**
 * Simulates the manifest's scenarios into a directory of this process's own, and publishes the
 * result only once every one of them has finished. Nothing partial is ever visible where results are
 * looked for.
 */
private fun run(
    manifest: LaunchManifest,
    workDir: Path,
) {
    val issues =
        manifest.scenarios.flatMap { scenario ->
            scenario.validate().map { "scenario ${scenario.id}: ${it.path} ${it.message}" }
        }
    if (issues.isNotEmpty()) {
        throw LaunchFailure(EXIT_INVALID_SPEC, issues.joinToString(prefix = "The manifest carries invalid scenarios: "))
    }

    val report =
        OpenDC
            .builder()
            .provisioner(provisioner(workDir.resolve("inputs")))
            .output(workDir.resolve("output"))
            .parallelism(manifest.parallelism.coerceIn(1, Runtime.getRuntime().availableProcessors()))
            .build()
            .simulate(RUN, manifest.scenarios)
    publish(report, manifest.results)
}

/**
 * Resolves the URIs the server wrote into the scenarios, bringing each one over at most once however
 * many concurrent runs ask for it.
 *
 * A name reaching this point is the server having failed to resolve it, not something to guess at.
 */
private fun provisioner(staging: Path): ResourceProvisioner {
    val resolved = ConcurrentHashMap<String, Path>()
    return ResourceProvisioner { reference ->
        val uri =
            when (reference) {
                is UriReference -> reference.uri
                is NamedReference ->
                    throw LaunchFailure(EXIT_INVALID_SPEC, "The manifest names '${reference.name}' instead of locating it")
            }
        val local = resolved.computeIfAbsent(uri) { fetch(it, staging.resolve(it.substringAfterLast('/'))) }
        object : ProvisionedResource {
            override val path: Path = local

            override fun close() {}
        }
    }
}

/** Copies each run's output to `<results>/<scenario>/seed=<seed>/`, where the server reads it. */
private fun publish(
    report: SimulationReport,
    results: String,
) {
    val root = writable(results)
    for (scenario in report.scenarios) {
        for (run in scenario.runs) {
            val from = run.outputPath ?: continue
            val to = root.resolve(scenario.scenario.id.toString()).resolve("seed=${run.seed}")
            to.createDirectories()
            for (file in from.listDirectoryEntries()) {
                copy(file, to.resolve(file.name))
            }
        }
    }
}

/**
 * A local, seekable path holding what [uri] names.
 *
 * A `file:` location is read where it lies, which is what a launcher beside the store sees; a remote
 * one is fetched to [target]. Only a single file can be fetched, so a workload trace, being a
 * directory of tables, has to be somewhere this process can reach directly.
 */
private fun fetch(
    uri: String,
    target: Path,
): Path {
    val parsed =
        try {
            URI.create(uri)
        } catch (e: IllegalArgumentException) {
            throw LaunchFailure(EXIT_INVALID_SPEC, "'$uri' is not a URI", e)
        }
    return when (parsed.scheme) {
        null -> existing(Path.of(uri))
        "file" -> existing(Path.of(parsed))
        "http", "https" -> download(parsed, target)
        else -> throw LaunchFailure(EXIT_TRANSFER_FAILED, "Cannot reach '$uri': the launcher reads file: and https: only")
    }
}

/** The directory [uri] names, which this process has to be able to write into. */
private fun writable(uri: String): Path {
    val parsed = URI.create(uri)
    val path =
        when (parsed.scheme) {
            null -> Path.of(uri)
            "file" -> Path.of(parsed)
            else -> throw LaunchFailure(EXIT_TRANSFER_FAILED, "'$uri' has to name a directory this process can write into")
        }
    try {
        return path.createDirectories()
    } catch (e: IOException) {
        throw LaunchFailure(EXIT_TRANSFER_FAILED, "Could not write into $path", e)
    }
}

private fun existing(path: Path): Path {
    if (!path.exists()) {
        throw LaunchFailure(EXIT_TRANSFER_FAILED, "$path does not exist")
    }
    return path
}

private fun copy(
    from: Path,
    to: Path,
) {
    try {
        from.copyTo(to, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: IOException) {
        throw LaunchFailure(EXIT_TRANSFER_FAILED, "Could not write $to", e)
    }
}

private fun download(
    uri: URI,
    target: Path,
): Path {
    target.parent.createDirectories()
    val response =
        try {
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().use { client ->
                client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofFile(target))
            }
        } catch (e: IOException) {
            throw LaunchFailure(EXIT_TRANSFER_FAILED, "Could not read $uri", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LaunchFailure(EXIT_TRANSFER_FAILED, "Interrupted while reading $uri", e)
        }
    if (response.statusCode() !in 200..299) {
        throw LaunchFailure(EXIT_TRANSFER_FAILED, "GET $uri answered ${response.statusCode()}")
    }
    return target
}

/**
 * The cause chain is walked because a run's failure arrives wrapped in the pool's. Anything
 * unrecognized counts as a simulation error, which is never retried.
 */
private fun exitCodeOf(error: Throwable): Int =
    generateSequence(error) { it.cause }.filterIsInstance<LaunchFailure>().firstOrNull()?.exitCode
        ?: EXIT_SIMULATION_ERROR
