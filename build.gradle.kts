plugins {
    alias(libs.plugins.kotlin.jvm)
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

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
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