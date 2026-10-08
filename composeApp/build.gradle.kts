plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    js(IR) {
        browser {
            binaries.executable()
        }
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(kotlin("stdlib-js"))

                // Compose runtime + foundation + material3
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.components.resources)

                // Coroutines, serialization, datetime
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")

                // Ktor for JS
                implementation("io.ktor:ktor-client-core:2.3.8")
                implementation("io.ktor:ktor-client-js:2.3.8")

                // Compose Web modules
                implementation("org.jetbrains.compose.web:web-core:1.6.10")
                implementation("org.jetbrains.compose.web:web-svg:1.6.10")

                // NPM dependencies
                implementation(npm("firebase", "11.0.0"))
                implementation(npm("@js-joda/core", "3.2.0"))
                implementation(npm("xlsx", "0.18.5"))
                implementation(npm("lottie-web", "5.12.2"))
            }
        }
    }
    // Suppress expect/actual beta warning
    targets.all {
        compilations.all {
            kotlinOptions.freeCompilerArgs += "-Xexpect-actual-classes"
        }
    }
}