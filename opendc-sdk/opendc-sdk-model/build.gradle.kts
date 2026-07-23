description = "OpenDC shared simulation data model"

plugins {
    `kotlin-library-conventions`
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.opendc.opendcCommon)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.module.kotlin)
}
