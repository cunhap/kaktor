plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    id("module.publication")
}

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    androidTarget {
        publishLibraryVariants("release")
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
    }
//    js()
//    iosX64()
//    iosArm64()
//    iosSimulatorArm64()
//    iosX64 {
//        binaries.framework {
//            baseName = "common"
//        }
//    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                //put your multiplatform dependencies here
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kermit.logging)
                implementation(libs.kotlin.reflect)
                implementation("app.softwork:kotlinx-uuid-core:0.0.22")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(kotlin("test-junit"))
                implementation(libs.kotlix.coroutines.test)
            }
        }
//        val iosMain by getting {
//            dependencies {
//                implementation(libs.kotlinx.coroutines.core.ios.arm.x64)
//            }
//        }
    }
}

android {
    namespace = "org.jetbrains.kotlinx.multiplatform.library.template"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    packaging {
        resources.excludes += "DebugProbesKt.bin"
    }
}
