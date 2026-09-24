import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ubuntuterm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ubuntuterm"
        minSdk = 28
        targetSdk = 34
        versionCode = 16
        versionName = "0.1.15"

        // Only arm64-v8a for the first release (99% of modern devices)
        ndk {
            abiFilters += "arm64-v8a"
        }

        // v0.1.14: Using pre-built .so compiled with NDK directly (not via Gradle).
        // The .so was compiled with: aarch64-linux-android28-clang++ -nostdlib
        // It only depends on bionic libc/libdl/liblog + libandroid (all present on device).
        // To recompile, see scripts/compile_native.sh

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // prefab = true  // v0.1.7: disabled — we don't import native libs from AARs
    }

    // v0.1.14: Using pre-built .so in jniLibs/ (compiled directly with NDK).
    // externalNativeBuild disabled to avoid Gradle CMake overhead.

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*"
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // === AndroidX core ===
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.1")

    // === Compose BOM + Material 3 ===
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // === Coroutines ===
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // === Terminal renderer ===
    //
    // Per separation principle: NO Termux. The terminal renderer and
    // VT100 parser are implemented from scratch in
    // `ui/terminal/TerminalBuffer.kt` + `ui/terminal/AnsiParser.kt`.
    // No Termux class, library, or asset is referenced anywhere.

    // === DataStore for persistent settings ===
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // === Networking (for rootfs download only) ===
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // === Tar extraction (Apache Commons Compress) ===
    // Used to extract the Ubuntu rootfs tarball. This is a JAR dependency
    // for I/O only — it does NOT replace any Ubuntu component.
    implementation("org.apache.commons:commons-compress:1.27.1")

    // === Testing ===
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.02"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
