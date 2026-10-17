# Third-party components

MoonTone's original code retains the top-level [MIT license](LICENSE). Imported components retain their own licenses; the APK is not described as MIT-only. License texts are included in the APK under `assets/licenses/` and in the source tree at `echolink-app/app/src/main/assets/licenses/`.

| Component | Source in this repository / upstream | License text |
| --- | --- | --- |
| Moonlight common C and imported Android Java/JNI integration | `echolink-app/app/src/main/jni/moonlight-core/`, `echolink-app/app/src/main/java/com/limelight/`; [Moonlight common C](https://github.com/moonlight-stream/moonlight-common-c), [Moonlight Android](https://github.com/moonlight-stream/moonlight-android) | `Moonlight-GPL-3.0.txt` (GNU GPLv3; retain upstream file notices) |
| ENet | `moonlight-common-c/enet/`; [upstream](https://github.com/lsalzman/enet) | `ENet-MIT.txt` |
| Reed-Solomon FEC | `moonlight-common-c/reedsolomon/rs.c` | `Reed-Solomon-BSD.txt` (notice copied from the imported source) |
| Opus 1.5.2 | `moonlight-core/libopus/`; [source release](https://github.com/xiph/opus/tree/v1.5.2) | `Opus-1.5.2.txt` |
| OpenSSL 1.1.1q | `moonlight-core/openssl/`; [source release](https://github.com/openssl/openssl/tree/OpenSSL_1_1_1q) | `OpenSSL-1.1.1q.txt` (OpenSSL and original SSLeay licenses) |
| Android NDK cpu-features | Built from NDK 27.0.12077973 `sources/android/cpufeatures/` | `Android-cpu-features-BSD.txt` |
| Bouncy Castle 1.77 | Gradle `bcprov-jdk18on` and `bcpkix-jdk18on`; [upstream license](https://github.com/bcgit/bc-java/blob/r1rv77/LICENSE.html) | `Bouncy-Castle-1.77.html` |
| AndroidX / Jetpack Compose / Material Components, Kotlin and kotlinx.coroutines, OkHttp / Okio | Gradle dependencies declared in `echolink-app/app/build.gradle.kts`; [AndroidX](https://android.googlesource.com/platform/frameworks/support/), [Material](https://github.com/material-components/material-components-android), [Kotlin](https://github.com/JetBrains/kotlin), [coroutines](https://github.com/Kotlin/kotlinx.coroutines), [OkHttp](https://github.com/square/okhttp), [Okio](https://github.com/square/okio) | `Apache-2.0.txt`; upstream notices remain applicable |

The Opus version was confirmed from the checked-in arm64 static library's version string. OpenSSL was confirmed from both the checked-in headers and arm64 static library. These libraries are inherited prebuilt inputs; the original import does not record a complete upstream commit and build provenance. The release source archive preserves these exact inputs alongside the locally built Java/Kotlin/JNI sources and build scripts; it does not claim the prebuilt archives were rebuilt from the upstream links above.

Release source and build information: [docs/RELEASE.md](docs/RELEASE.md). The `v0.3.0` Git tag and its source archive identify the source tree used for that release. Upstream license texts copied for this release are unmodified; the two BSD notices extracted from source headers preserve their original text.
