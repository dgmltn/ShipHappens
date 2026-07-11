plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "com.shiphappens.source.api"
        compileSdk = providers.gradleProperty("shiphappens.compileSdk").get().toInt()
        minSdk = providers.gradleProperty("shiphappens.minSdk").get().toInt()
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        all { languageSettings.optIn("kotlin.time.ExperimentalTime") }
        commonMain.dependencies {
            api(projects.core.model)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
