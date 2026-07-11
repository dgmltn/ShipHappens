plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "com.shiphappens.core.model"
        compileSdk = providers.gradleProperty("shiphappens.compileSdk").get().toInt()
        minSdk = providers.gradleProperty("shiphappens.minSdk").get().toInt()
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        all { languageSettings.optIn("kotlin.time.ExperimentalTime") }
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
