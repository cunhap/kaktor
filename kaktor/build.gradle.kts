plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    id("module.publication")
    alias(libs.plugins.kotlinSerialization)
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
//    js {
//        nodejs()
//        browser {
//            testTask {
//                useKarma {
//                    useChromeHeadless()
//                }
//            }
//        }
//    }
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
                implementation(libs.kotlinx.uuid.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(kotlin("test-junit"))
                implementation(libs.kotlix.coroutines.test)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.kotlinx.serialization.protobuf)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.clio)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.netty)
                implementation(libs.ktor.network)
                implementation(libs.ktor.network.tls)
                implementation(libs.ktor.server.websockets)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.serialize.protobuf)
                implementation(libs.logback.classic)
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
dependencies {
    implementation("io.ktor:ktor-client-cio-jvm:2.3.12")
}
