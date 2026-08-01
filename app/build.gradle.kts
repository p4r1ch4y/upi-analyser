import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing.
 *
 * Read from `keystore.properties`, which is git-ignored and never leaves the
 * machine that holds the key. When the file is absent the release build is left
 * *unsigned* rather than falling back to the debug key.
 *
 * Falling back mattered: every release APK built here was signed
 * `CN=Android Debug`, a certificate whose private key ships inside the Android
 * SDK and is therefore identical on every machine on earth. Play Protect treats
 * that as an unsigned app by any other name, which is a large part of why
 * sideloading this triggered a warning.
 *
 * F-Droid signs with its own key, so an unsigned release is exactly what its
 * build server expects.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.spendlens"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.spendlens"
        minSdk = libs.versions.minSdk.get().toInt()  // Android 8.0 - SQLCipher minimum
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 4
        versionName = "0.1.3-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // v1 is what Play Protect's older heuristics still look at; v2/v3
                // are what modern Android verifies. Sign with all of them.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Null when there is no keystore.properties: unsigned, never debug-signed.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    flavorDimensions += "version"
    productFlavors {
        create("standard") {
            dimension = "version"
            // No SMS permissions - notification only
        }
        create("full") {
            dimension = "version"
            // Includes SMS permissions for F-Droid
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}


dependencies {
    // Core modules
    implementation(project(":core:model"))
    implementation(project(":core:parser"))
    implementation(project(":core:resolution"))
    implementation(project(":core:database"))
    implementation(project(":core:fusion"))

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    // NOTE: no androidx.work here on purpose. WorkManager merges WAKE_LOCK and
    // ACCESS_NETWORK_STATE into the manifest, and nothing in this app schedules
    // deferred work. The permission list is part of the product claim.

    // Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore (for settings)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
