plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "com.shiphappens.ui"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
        // Host tests: un-mocked Android SDK methods return default values instead of throwing
        // "not mocked" (e.g. Room's RoomDatabase.isMainThread calls Looper.getMainLooper()).
        withHostTestBuilder {}.configure { isReturnDefaultValues = true }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SharedUI"
            isStatic = true
        }
    }
    sourceSets {
        all { languageSettings.optIn("kotlin.time.ExperimentalTime") }
        commonMain.dependencies {
            api(projects.core.data)
            implementation(projects.source.trackingmore)
            implementation(projects.source.demo)
            implementation(projects.source.ups)
            implementation(projects.source.usps)
            implementation(projects.source.fedex)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.navigation3.runtime)
            implementation(libs.navigation3.ui)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.kotlinx.serialization.json)
        }
        getByName("androidHostTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project.dependencies.platform(libs.koin.bom))
                implementation(libs.koin.test)
                // Host tests run on the desktop JVM, but this source set resolves KMP deps to
                // their Android variants: `sqlite-bundled` becomes sqlite-bundled-android,
                // whose JNI library only ships Android ABIs and can't load on a mac/linux
                // host. Add the desktop artifact explicitly (and see the excludes below) so
                // BundledSQLiteDriver has usable natives.
                implementation(libs.sqlite.bundled.jvm)
            }
        }
    }
}

compose.resources { packageOfResClass = "com.shiphappens.ui.res" }

// Companion to the sqlite-bundled-jvm test dependency above: core:data's androidMain still
// pulls the Android-native `sqlite-bundled` variant onto this classpath transitively, giving
// two BundledSQLiteDriver class definitions whose winner would depend on classpath order.
// Exclude the Android variant so the desktop driver wins deterministically in host tests.
configurations.matching { it.name.startsWith("androidHostTest") }.configureEach {
    exclude(group = "androidx.sqlite", module = "sqlite-bundled")
    exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
}
