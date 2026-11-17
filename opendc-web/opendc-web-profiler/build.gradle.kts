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

description = "Offline benchmark of the web launcher, the measurements behind the resource estimator"

plugins {
    `kotlin-conventions`
    `testing-conventions`
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    applicationName = "opendc-profiler"
    mainClass.set("org.opendc.web.profiler.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

evaluationDependsOn(":opendc-web:opendc-web-launcher")
val launcher = project(":opendc-web:opendc-web-launcher")
val launcherInstall = launcher.tasks.named("installDist")
val launcherLib = launcher.layout.buildDirectory.dir("install/opendc-launcher/lib")
val publicGrid = layout.projectDirectory.file("src/main/resources/grids/public.json")
val measurements = layout.buildDirectory.dir("profiler")

distributions {
    main {
        contents {
            from(launcherInstall) { into("launcher") }
            from("das5") { into("das5") }
            from("src/main/resources/grids") { into("grids") }
        }
    }
}

tasks.register<JavaExec>("profile") {
    group = "profiling"
    description = "Measure the public grid on this machine, into build/profiler"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.opendc.web.profiler.MainKt")
    workingDir = rootDir
    dependsOn(launcherInstall)
    args(
        "measure",
        "--grid",
        publicGrid.asFile.path,
        "--launcher-lib",
        launcherLib.get().asFile.path,
        "--out",
        measurements.get().asFile.path,
    )
}

val uvSync =
    tasks.register<Exec>("uvSync") {
        group = "profiling"
        description = "Install the plotting package's locked Python environment with uv"
        workingDir = projectDir
        commandLine("uv", "sync", "--frozen")
    }

tasks.register<Exec>("plots") {
    group = "profiling"
    description = "Render the figures from build/profiler into build/profiler/figures"
    dependsOn(uvSync)
    workingDir = projectDir
    commandLine(
        "uv",
        "run",
        "opendc-profiler-plots",
        measurements.get().asFile.path,
        measurements.get().dir("figures").asFile.path,
    )
}

tasks.register<Exec>("pythonTest") {
    group = "verification"
    description = "Run the plotting package's pytest suite"
    dependsOn(uvSync)
    workingDir = projectDir
    commandLine("uv", "run", "pytest")
}

val wheel = layout.buildDirectory.dir("python/wheel")
val requirements = layout.buildDirectory.file("python/requirements.txt")

val uvBuild =
    tasks.register<Exec>("uvBuild") {
        group = "profiling"
        description = "Build the plotting package's wheel into build/python/wheel"
        dependsOn(uvSync)
        workingDir = projectDir
        inputs.files("pyproject.toml", "uv.lock")
        inputs.dir("src/main/python")
        outputs.dir(wheel)
        commandLine("uv", "build", "--wheel", "--out-dir", wheel.get().asFile.path)
    }

val uvExport =
    tasks.register<Exec>("uvExport") {
        group = "profiling"
        description = "Write the plotting package's pinned dependencies to build/python/requirements.txt"
        workingDir = projectDir
        inputs.files("pyproject.toml", "uv.lock")
        outputs.file(requirements)
        commandLine(
            "uv", "export", "--format", "requirements-txt", "--no-dev", "--no-emit-project", "--no-hashes",
            "--output-file", requirements.get().asFile.path,
        )
    }

tasks.register<Tar>("das5Bundle") {
    group = "profiling"
    description = "Bundle the distribution, the launcher and the plotting wheel for upload to DAS-5"
    archiveFileName.set("opendc-profiler-das5.tar")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(tasks.named("installDist")) { into("opendc-profiler") }
    from(uvBuild) { into("opendc-profiler/python") }
    from(uvExport) { into("opendc-profiler/python") }
}

dependencies {
    implementation(projects.opendcWeb.opendcWebDispatcher)
    implementation(projects.opendcWeb.opendcWebLauncher)
    implementation(projects.opendcTrace.opendcTraceApi)
    implementation(projects.opendcTrace.opendcTraceParquet)
    implementation(libs.clikt5)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.logging)

    runtimeOnly(libs.log4j.core)
    runtimeOnly(libs.log4j.slf4j)
}
