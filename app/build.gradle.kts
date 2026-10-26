import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
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

// AGP cannot create an AAB in the same configuration whose APK outputs use
// ABI splits and resource shrinking. Release/CI therefore build the AAB in a
// separate invocation with -Prekamin.abiSplits=false.
val rekaminAbiSplitsEnabled = providers.gradleProperty("rekamin.abiSplits")
    .map { it.toBoolean() }
    .getOrElse(true)

android {
    namespace = "id.rekaminaja.app"
    // compileSdk 37 = Android 17 (terbaru 2026). targetSdk 36 = syarat Google Play
    // per 31 Agu 2026 + perilaku runtime paling stabil untuk MediaProjection.
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    // Samakan dengan modul Whisper agar AGP menemukan llvm-strip untuk
    // membuang debug symbols dari native library pada APK release.
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "id.rekaminaja.app"
        minSdk = 24 // Android 7.0
        targetSdk = 36 // Android 16
        versionCode = 6
        versionName = "1.3.0"
        vectorDrawables { useSupportLibrary = true }
    }

    // Full membawa Whisper lokal untuk ARM64 dan ARM 32-bit. Lite tetap
    // universal dan jauh lebih kecil untuk x86 atau pengguna yang tidak
    // memerlukan Auto Subtitle; semua fitur perekam lainnya tetap tersedia.
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
        }
        create("lite") {
            dimension = "edition"
        }
    }

    // Rilis GitHub/APKPure menyediakan APK Full khusus arm64-v8a dan
    // armeabi-v7a. Universal output dipakai untuk edisi Lite.
    splits {
        abi {
            isEnable = rekaminAbiSplitsEnabled
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
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
    add("fullImplementation", libs.kotlinx.coroutines.android)
    add("fullImplementation", project(":whisper-android"))
}

// Model Auto Subtitle diunduh hanya di mesin build/CI lalu dibundel sebagai
// asset. Aplikasi tetap tidak memiliki izin INTERNET dan transkripsi berjalan
// sepenuhnya offline di perangkat.
val whisperModelName = "ggml-tiny-q5_1.bin"
val whisperModelExpectedSize = 32_152_673L
val whisperModelExpectedSha256 =
    "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"
val whisperModelUrl =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/" +
        "5359861c739e955e79d9a303bcbc70fb988958b1/$whisperModelName"
val whisperModelFile = layout.projectDirectory
    .file("src/full/assets/models/$whisperModelName")
    .asFile

fun whisperSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val downloadWhisperModel by tasks.registering {
    group = "build"
    description = "Download and verify the bundled offline Whisper model"
    outputs.file(whisperModelFile)

    doLast {
        if (whisperModelFile.isFile &&
            whisperModelFile.length() == whisperModelExpectedSize &&
            whisperSha256(whisperModelFile) == whisperModelExpectedSha256
        ) {
            logger.lifecycle("Whisper Auto Subtitle model already verified.")
            return@doLast
        }

        val parent = whisperModelFile.parentFile.apply { mkdirs() }
        val temp = File(parent, "$whisperModelName.download")
        temp.delete()

        var url = URI(whisperModelUrl).toURL()
        var downloaded = false
        for (redirect in 0..8) {
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "RekaminAja-Gradle/1.1.0")

            when (val code = connection.responseCode) {
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException(
                            "Whisper model redirect without a Location header"
                        )
                    url = URI(url.toString()).resolve(location).toURL()
                    connection.disconnect()
                    if (redirect == 8) {
                        throw IllegalStateException("Too many Whisper model redirects")
                    }
                }
                in 200..299 -> {
                    connection.inputStream.use { input ->
                        FileOutputStream(temp).use { output -> input.copyTo(output) }
                    }
                    downloaded = true
                    break
                }
                else -> {
                    connection.disconnect()
                    temp.delete()
                    throw IllegalStateException(
                        "Failed to download Whisper model: HTTP $code"
                    )
                }
            }
        }

        if (!downloaded || temp.length() != whisperModelExpectedSize) {
            val actualSize = if (temp.exists()) temp.length() else -1L
            temp.delete()
            throw IllegalStateException(
                "Whisper model size mismatch: $actualSize, " +
                    "expected $whisperModelExpectedSize"
            )
        }
        val actualSha256 = whisperSha256(temp)
        if (actualSha256 != whisperModelExpectedSha256) {
            temp.delete()
            throw IllegalStateException(
                "Whisper model SHA-256 mismatch: $actualSha256"
            )
        }

        if (whisperModelFile.exists()) whisperModelFile.delete()
        if (!temp.renameTo(whisperModelFile)) {
            temp.copyTo(whisperModelFile, overwrite = true)
            temp.delete()
        }
        logger.lifecycle("Whisper Auto Subtitle model downloaded and verified.")
    }
}

tasks.configureEach {
    if (name == "preFullDebugBuild" || name == "preFullReleaseBuild") {
        dependsOn(downloadWhisperModel)
    }
}
