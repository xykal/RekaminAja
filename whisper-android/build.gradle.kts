plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.ffmpegkit.whisper"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")

        // RekaminAja builds the MIT-licensed Whisper Android 1.0.0 sources
        // locally so the Full edition covers modern 64-bit ARM and legacy
        // 32-bit ARM devices. The published Maven AAR is arm64-v8a only.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE",
                    "-DGGML_NEON=ON",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_METAL=OFF"
                )
                cppFlags += "-O3"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
