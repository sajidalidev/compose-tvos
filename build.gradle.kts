import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-gradle-plugin`
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.plugin.publish)
}

group = property("GROUP").toString()

// ---------------------------------------------------------------------------
// Functional-test plugin version (content-derived).
//
// The functional tests publish this plugin into build/functional-test-repo and
// resolve it through a PERSISTENT shared TestKit home (build/functional-test/
// testkit). Gradle caches static versions in that home's modules-2 cache
// indefinitely and never re-resolves a republished same-version coordinate from
// a file:// repo — so republishing changed plugin code under the static VERSION
// would make functional tests silently validate STALE plugin bytes.
//
// Fix: for test-only invocations the effective version is
// "<VERSION>-ft-<hash>", where <hash> is a stable digest of every file under
// src/main (relative paths + bytes). Unchanged source -> unchanged version
// (test task stays up-to-date); any source edit -> brand-new coordinate that
// busts the TestKit module cache. Old -ft-* versions accumulate as small
// entries in build/functional-test-repo (dropped by `clean`) and in the TestKit
// cache — an accepted cost.
//
// Real publishes are untouched: the override applies ONLY when a test-carrying
// task (test/check/build) was requested AND no real publish task was. Because
// abbreviated task names (e.g. `pTML`) can evade this configuration-time check,
// a taskGraph.whenReady guard below additionally FAILS the build if a real
// publish task ever ends up in the graph while an -ft- version is active.
fun srcMainContentHash(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val root = layout.projectDirectory.dir("src/main").asFile
    root.walkTopDown()
        .filter { it.isFile }
        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        .forEach { file ->
            digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(file.readBytes())
        }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(10)
}

val baseVersion = property("VERSION").toString()
val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
val realPublishRequested = requestedTaskNames.any {
    it.startsWith("publish") && it != "publishAllPublicationsToFunctionalTestRepository"
}
val testCarryingTaskRequested = requestedTaskNames.any { it == "test" || it == "check" || it == "build" }
val useFunctionalTestVersion = testCarryingTaskRequested && !realPublishRequested

version = if (useFunctionalTestVersion) "$baseVersion-ft-${srcMainContentHash()}" else baseVersion

// Safety net for the abbreviation loophole above: never let an -ft- version
// reach a real repository (Maven Central, Plugin Portal, mavenLocal).
gradle.taskGraph.whenReady {
    if (!project.version.toString().contains("-ft-")) return@whenReady
    val offending = allTasks.filter { task ->
        (task is org.gradle.api.publish.maven.tasks.PublishToMavenRepository &&
            !task.name.endsWith("ToFunctionalTestRepository")) ||
            task is org.gradle.api.publish.maven.tasks.PublishToMavenLocal ||
            task.name == "publishPlugins"
    }
    if (offending.isNotEmpty()) {
        throw GradleException(
            "Refusing to publish functional-test version '${project.version}' to a real repository " +
                "(tasks: ${offending.joinToString { it.path }}). Run the publish in its own " +
                "invocation, without test/check/build."
        )
    }
}

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