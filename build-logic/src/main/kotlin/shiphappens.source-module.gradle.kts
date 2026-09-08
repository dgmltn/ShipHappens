// One carrier module. Applies the KMP + serialization + Android KMP library plugins, the shared
// targets and opt-ins, and the dependencies every carrier needs. The Android namespace derives
// from the project name (`:source:ups` -> com.dgmltn.shiphappens.source.ups).
import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
}

val libs = the<LibrariesForLibs>()

kotlin {
    android {
        // Hyphens are not legal in a package segment, so strip them from the project name.
        namespace = "com.dgmltn.shiphappens.source.${project.name.replace("-", "")}"
        compileSdk = providers.gradleProperty("shiphappens.compileSdk").get().toInt()
        minSdk = providers.gradleProperty("shiphappens.minSdk").get().toInt()
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        all { languageSettings.optIn("kotlin.time.ExperimentalTime") }
        commonMain.dependencies {
            api(project(":source:api"))
            api(project(":source:webview"))
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":source:webview-testing"))
        }
    }
}
