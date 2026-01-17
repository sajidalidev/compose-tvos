plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
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
        create("composeTvos") {
            id = "dev.sajidali.compose-tvos"
            displayName = "Compose tvOS"
            description = "Redirects JetBrains Compose Multiplatform tvOS dependencies to dev.sajidali.* artifacts"
            tags.set(listOf("kotlin", "compose", "multiplatform", "tvos", "dependency-redirect"))
            implementationClass = "dev.sajidali.compose.tvos.ComposeTvosRedirectPlugin"
        }
    }
}

publishing {
    repositories {
        mavenLocal()
    }
}
