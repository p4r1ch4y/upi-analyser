plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("app.cash.sqldelight")
}

android {
    namespace = "com.spendlens.core.database"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

sqldelight {
    databases {
        create("SpendLensDatabase") {
            packageName.set("com.spendlens.core.database")
            dialect("app.cash.sqldelight:sqlite-3-35-dialect:2.0.2")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    
    implementation("app.cash.sqldelight:android-driver:2.0.2")
    implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")
    implementation("net.zetetic:sqlcipher-android:4.5.6@aar")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")
    
    testImplementation("junit:junit:4.13.2")
}
