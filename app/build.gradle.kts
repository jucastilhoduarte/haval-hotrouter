plugins {
    id("com.android.application")
}

android {
    namespace = "com.castilhoduarte.jlh6"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.castilhoduarte.jlh6"
        minSdk = 28
        //noinspection ExpiredTargetSdkVersion  // target 28 on purpose: head-unit compat
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        named("release") {
            // Only sign release if the keystore is present (CI on main). Local/PR debug
            // builds don't need it.
            if (file("release.keystore").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// No third-party dependencies. Android SDK only.
dependencies {
}
