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

description = "Web server of OpenDC"

plugins {
    kotlin("jvm")
    kotlin("plugin.allopen")
    alias(libs.plugins.kotlin.serialization)
    `quarkus-conventions`
    application
}

application {
    applicationName = "opendc-server"
    mainClass.set("io.quarkus.bootstrap.runner.QuarkusEntryPoint")
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

// The OpenDC simulation modules route SLF4J through Log4j2 and expose the Log4j2 SLF4J binding
// (log4j-slf4j2-impl) as an api dependency; it leaks transitively via opendc-sdk-model ->
// opendc-common, leaving SLF4J with two competing providers under Quarkus' JBoss LogManager.
// Drop the Log4j2 SLF4J binding here so SLF4J binds deterministically.
configurations.all {
    exclude(group = "org.apache.logging.log4j", module = "log4j-slf4j2-impl")
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))

    implementation(projects.opendcSdk.opendcSdkModel)
    implementation(projects.opendcWeb.opendcWebDispatcher)
    // For the canonical trace table names, and for the readers that validate an upload.
    implementation(projects.opendcTrace.opendcTraceApi)

    implementation(libs.quarkus.kotlin)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.rest.kotlin.serialization)
    implementation(libs.quarkus.smallrye.openapi)

    implementation(libs.quarkus.hibernate.orm.panache.kotlin)
    implementation(libs.quarkus.hibernate.validator)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.jdbc.h2)
    implementation(libs.quarkus.quinoa.runtime)

    implementation(enforcedPlatform(libs.aws.bom))
    implementation(libs.aws.s3)
    // The lightest of the SDK's HTTP clients. Uploads are streamed from a spooled file, so nothing
    // here needs connection pooling or async.
    implementation(libs.aws.url.connection.client)

    testImplementation(libs.quarkus.junit5.core)
    testImplementation(libs.quarkus.jacoco)
    testImplementation(libs.restassured.core)
}

// Quinoa builds the frontend itself from ui-dir during quarkusBuild; the frontend module's own
// nextBuild task stays as the standalone assemble/check path. Never run both pnpm processes in
// the same directory concurrently under a parallel root build.
tasks.quarkusBuild {
    mustRunAfter(":opendc-web:opendc-web-frontend:nextBuild")
}

// The application plugin generates the start scripts; the Quarkus fast-jar launcher carries its
// own classpath manifest, so it is the only entry the scripts need.
tasks.startScripts {
    classpath = files("lib/quarkus-run.jar")
}

val quarkusAppDir = layout.buildDirectory.dir("quarkus-app")

distributions {
    main {
        distributionBaseName.set("opendc")

        contents {
            from("../../LICENSE.txt")
            from("config") {
                into("config")
            }
            from(tasks.quarkusBuild) {
                into("lib")
            }
            from("../../traces") {
                into("traces")
            }
            // The application plugin hard-wires the module jar and the plain runtime classpath
            // into lib/ with no removal API. The Quarkus fast-jar under build/quarkus-app already
            // bundles every dependency, so those defaults would ship each jar twice. Keep only
            // jars that come out of the Quarkus build.
            exclude { element ->
                element.name.endsWith(".jar") &&
                    !element.file.toPath().startsWith(quarkusAppDir.get().asFile.toPath())
            }
        }
    }
}
