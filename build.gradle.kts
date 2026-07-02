plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-gradle-plugin`
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.plugin.publish)
}

group = property("GROUP").toString()
version = property("VERSION").toString()

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(gradleApi())
    implementation(gradleKotlinDsl())
    implementation(libs.kotlinx.serialization.json)

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
}

// ---------------------------------------------------------------------------
// Functional test wiring (GradleTestKit)
//
// TestKit's withPluginClasspath() does NOT inject into settings scripts, so the
// plugin (and its plugin-marker publications) are published to a local Maven repo
// that the functional consumer projects point their pluginManagement at.
// ---------------------------------------------------------------------------
val functionalTestRepo: Provider<Directory> = layout.buildDirectory.dir("functional-test-repo")

publishing {
    repositories {
        maven {
            name = "functionalTest"
            url = uri(functionalTestRepo)
        }
    }
}

// vanniktech's signAllPublications() signs every publication; no GPG key is available
// for local/CI functional-test publishing. Sign tasks therefore only run when the task
// graph publishes anywhere OTHER than the functional-test repository — a test-only
// invocation skips signing, while real releases (Maven Central, mavenLocal) keep it,
// even if tests run in the same invocation.
tasks.withType<org.gradle.plugins.signing.Sign>().configureEach {
    onlyIf("signing is only skipped for functional-test-only publishing") {
        gradle.taskGraph.allTasks.any { task ->
            (task is org.gradle.api.publish.maven.tasks.PublishToMavenRepository &&
                !task.name.endsWith("ToFunctionalTestRepository")) ||
                task is org.gradle.api.publish.maven.tasks.PublishToMavenLocal
        }
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn("publishAllPublicationsToFunctionalTestRepository")
    systemProperty("functionalTest.pluginRepo", functionalTestRepo.get().asFile.absolutePath)
    systemProperty("functionalTest.pluginVersion", version.toString())
    // Shared TestKit home: keeps the Kotlin plugin / stdlib downloads cached across
    // test runs instead of re-downloading them into a fresh temp dir every run.
    systemProperty(
        "functionalTest.testKitDir",
        layout.buildDirectory.dir("functional-test/testkit").get().asFile.absolutePath
    )
}

gradlePlugin {
    website.set("https://github.com/sajidalidev/compose-tvos")
    vcsUrl.set("https://github.com/sajidalidev/compose-tvos")

    plugins {
        create("composeTvosSettings") {
            id = "dev.sajidali.compose-tvos"
            displayName = "Compose tvOS"
            description = "Adds tvOS support to JetBrains Compose Multiplatform by injecting tvOS variants from alternative artifacts."
            tags.set(listOf("kotlin", "compose", "multiplatform", "tvos", "apple", "kotlin-native"))
            implementationClass = "dev.sajidali.compose.tvos.ComposeTvosRedirectSettingsPlugin"
        }
        create("composeTvosProject") {
            id = "dev.sajidali.compose-tvos-project"
            displayName = "Compose tvOS (Project Plugin)"
            description = "Project plugin for Compose tvOS. Use the settings plugin (dev.sajidali.compose-tvos) instead."
            tags.set(listOf("kotlin", "compose", "multiplatform", "tvos"))
            implementationClass = "dev.sajidali.compose.tvos.ComposeTvosRedirectPlugin"
        }
    }
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates(group.toString(), "compose-tvos", version.toString())

    pom {
        name.set("Compose tvOS")
        description.set("A Gradle plugin that adds tvOS support to JetBrains Compose Multiplatform projects by injecting tvOS variants from alternative artifacts.")
        url.set("https://github.com/sajidalidev/compose-tvos")
        inceptionYear.set("2025")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("sajidalidev")
                name.set("Sajid Ali")
                email.set("sajidalidev@users.noreply.github.com")
                url.set("https://github.com/sajidalidev")
            }
        }

        scm {
            url.set("https://github.com/sajidalidev/compose-tvos")
            connection.set("scm:git:git://github.com/sajidalidev/compose-tvos.git")
            developerConnection.set("scm:git:ssh://git@github.com/sajidalidev/compose-tvos.git")
        }

        issueManagement {
            system.set("GitHub Issues")
            url.set("https://github.com/sajidalidev/compose-tvos/issues")
        }
    }
}