import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Signing release dibaca dari keystore.properties (di-gitignore) atau env CI.
// Kalau tidak ada, build release tetap jalan tanpa signing (unsigned) —
// GitHub Actions menyuntikkan keystore lewat secrets.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv("REKAMIN_" + key.uppercase())

android {
    namespace = "id.rekaminaja.app"
    // compileSdk 37 = Android 17 (terbaru 2026). targetSdk 36 = syarat Google Play
    // per 31 Agu 2026 + perilaku runtime paling stabil untuk MediaProjection.
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "id.rekaminaja.app"
        minSdk = 24 // Android 7.0
        targetSdk = 36 // Android 16
        versionCode = 9
        versionName = "1.5.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (signingValue("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(signingValue("storeFile")!!)
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("keyAlias")
                keyPassword = signingValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
}
