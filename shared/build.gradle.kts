plugins {
    kotlin("multiplatform") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
}
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework { baseName = "IZZShared"; isStatic = true }
    }
    sourceSets {
        commonMain.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
