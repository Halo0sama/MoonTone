plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    ndkVersion = "27.0.12077973"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    namespace = "com.halo.moontone"

    defaultConfig {
        applicationId = "com.halo.moontone"
        // material 1.14（M3 Expressive 窗口主题）与 material3-ripple 1.5 均要求 minSdk ≥ 24
        minSdk = 24
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // armeabi-v7a 的预编译 libopus.a 在网盘备份中损坏，暂时只出 arm64（目标手机为 arm64）
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }
}

dependencies {
    // Compose BOM 2026.09.00 + material3 1.5.0-alpha29（Material 3 Expressive）
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-alpha29")
    implementation("androidx.compose.foundation:foundation")
    // Expressive outlined/rounded iconography (Rounded style used across the redesign)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.12.0")
    // M3 Expressive 窗口主题（Theme.Material3Expressive）
    implementation("com.google.android.material:material:1.14.0")

    // Moonlight crypto
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")

    // OkHttp (for pairing to Sunshine)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
