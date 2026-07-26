plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

android {
    namespace = "com.spendlens.core.database"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


sqldelight {
    databases {
        create("SpendLensDatabase") {
            packageName.set("com.spendlens.core.database")
            dialect(libs.sqldelight.sqlite.dialect)
        }
    }
}

dependencies {
    api(project(":core:model"))

    api(libs.sqldelight.android.driver)
    api(libs.sqldelight.coroutines.extensions)
    api(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
