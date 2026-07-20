plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.dgmltn.shiphappens.android"
    compileSdk = providers.gradleProperty("shiphappens.compileSdk").get().toInt()
    defaultConfig {
        applicationId = "com.dgmltn.shiphappens"
        minSdk = providers.gradleProperty("shiphappens.minSdk").get().toInt()
        targetSdk = providers.gradleProperty("shiphappens.targetSdk").get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(projects.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
}
