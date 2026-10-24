# RekaminAja tidak memakai reflection/serialization library secara langsung.
# Bridge JNI Whisper dipertahankan agar nama kelas/metode native tetap cocok
# dengan library .so pada build release.
-keep class dev.ffmpegkit.whisper.** { *; }
-keepclassmembers class dev.ffmpegkit.whisper.** {
    native <methods>;
}
