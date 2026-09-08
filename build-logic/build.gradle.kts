plugins {
    `kotlin-dsl`
}

// The Gradle plugin marker artifact for a plugin id, so the precompiled script plugins below can
// apply it by id. Versions come from the catalog; nothing is pinned here.
fun Provider<PluginDependency>.asArtifact() = map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

dependencies {
    implementation(libs.plugins.kotlinMultiplatform.asArtifact())
    implementation(libs.plugins.kotlinSerialization.asArtifact())
    implementation("com.android.kotlin.multiplatform.library:com.android.kotlin.multiplatform.library.gradle.plugin:${libs.versions.agp.get()}")
    // Exposes the generated `libs` catalog accessors (LibrariesForLibs) to the precompiled scripts.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
