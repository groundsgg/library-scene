import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("gg.grounds.base-conventions") version "0.8.0" apply false
    kotlin("jvm") version "2.2.20" apply false
}

subprojects {
    group = "gg.grounds"
    version = findProperty("versionOverride")?.toString() ?: "0.1.0-SNAPSHOT"

    apply(plugin = "gg.grounds.base-conventions")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(25) }
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
        withSourcesJar()
    }
    tasks.withType<KotlinCompile>().configureEach { compilerOptions.jvmTarget.set(JvmTarget.JVM_25) }

    repositories {
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/groundsgg/*")
            credentials {
                username = providers.gradleProperty("github.user").orNull ?: System.getenv("GITHUB_USER") ?: System.getenv("GITHUB_ACTOR") ?: ""
                password = providers.gradleProperty("github.token").orNull ?: System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                artifactId = project.name
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/groundsgg/library-scene")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}
