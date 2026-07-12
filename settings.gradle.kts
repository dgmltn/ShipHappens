rootProject.name = "ShipHappens"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google { mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google { mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") } }
        mavenCentral()
    }
}

include(":domain")
include(":data")
include(":design")
include(":source:api")
include(":source:demo")
include(":source:ups")
include(":source:usps")
include(":source:fedex")
include(":ui")
include(":app-android")
