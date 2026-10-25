# Whisper Android (local RekaminAja build)

This module is derived from Whisper Android 1.0.0, commit
`97fbac037e3a64a6e12d0efd8fa964ad6e249d87`:

https://github.com/ffmpegkit-maintained/whisper/tree/v1.0.0

The Kotlin API and JNI bridge are kept compatible with the published
`dev.ffmpegkit-maintained:whisper-android:1.0.0` artifact. RekaminAja builds
the sources locally for both `arm64-v8a` and `armeabi-v7a`; the published
Maven AAR contains only `arm64-v8a` native code.

Native inference uses whisper.cpp v1.7.5, pinned as the
`third_party/whisper.cpp` Git submodule at commit
`51c6961c7b64b406833f4b6a4a20e67142f69225`.

See the repository-level `THIRD_PARTY_NOTICES.md` for copyright and license
information. Local build changes are limited to the Gradle/CMake paths, the
additional 32-bit ARM ABI, and its atomic-library link requirement.
