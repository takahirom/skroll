import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
  id("com.vanniktech.maven.publish") version libs.versions.mavenPublish apply false
}
allprojects {
  this.plugins.withId("com.vanniktech.maven.publish") {
    project.group = "io.github.takahirom.skroll"
    configure<MavenPublishBaseExtension> {
      publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
      signAllPublications()
    }
  }
}