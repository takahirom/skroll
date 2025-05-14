plugins {
  // Apply the shared build logic from a convention plugin.
  // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
  id("buildsrc.convention.kotlin-jvm")
  // Apply Kotlin Serialization plugin from `gradle/libs.versions.toml`.
  alias(libs.plugins.kotlinPluginSerialization)

  id("com.vanniktech.maven.publish")
}

dependencies {
  testImplementation(kotlin("test"))
  implementation(libs.kotlinxCoroutines)
}