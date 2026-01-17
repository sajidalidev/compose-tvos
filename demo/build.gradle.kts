plugins {
    kotlin("multiplatform") version "2.0.21"
    kotlin("plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
    id("dev.sajidali.compose-tvos-redirect") version "1.0.0"
}

// Configure the redirect plugin to use test version
composeTvosRedirect {
    targetVersion.set("9999.0.0-SNAPSHOT")
    verbose.set(true)
}

kotlin {
    // Custom named targets
    tvosArm64("tvosDevice")
    tvosSimulatorArm64("tvosSim")
    tvosX64("tvosX64Sim")

    // Also add iOS to verify it's NOT redirected
    iosArm64("iosDevice")
    iosSimulatorArm64("iosSim")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.ui)
            implementation(compose.foundation)
            implementation(compose.runtime)
        }

        // Custom intermediate source set for all tvOS targets
        val tvMain by creating {
            dependsOn(commonMain.get())
        }

        // Custom intermediate source set for all iOS targets
        val mobileMain by creating {
            dependsOn(commonMain.get())
        }

        // Link tvOS targets to custom source set
        getByName("tvosDeviceMain").dependsOn(tvMain)
        getByName("tvosSimMain").dependsOn(tvMain)
        getByName("tvosX64SimMain").dependsOn(tvMain)

        // Link iOS targets to custom source set
        getByName("iosDeviceMain").dependsOn(mobileMain)
        getByName("iosSimMain").dependsOn(mobileMain)
    }
}
