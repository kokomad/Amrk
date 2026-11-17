plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ksPath: String? = System.getenv("KEYSTORE_PATH")
val hasKeystore = ksPath != null && project.file(ksPath).exists()

android {
    namespace = "com.khader.autorun"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.khader.autorun"
        minSdk = 27
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            if (hasKeystore) {
                storeFile = project.file(ksPath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasKeystore) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
