plugins {
    id("com.android.application")
}

android {
    namespace = "com.discover.embypatcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.discover.embypatcher"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "2.9.2-beta"
    }
    // Release signing from environment variables (set by CI from repository secrets).
    // Keep the same keystore for every release, otherwise a new patcher version cannot
    // be installed over the old one (and its Emby signing key would be lost).
    val ksFile = System.getenv("PATCHER_KEYSTORE")
    signingConfigs {
        if (ksFile != null && file(ksFile).isFile) {
            create("release") {
                storeFile = file(ksFile)
                // Trim: a value pasted into a GitHub secret often ends with a line break.
                val storePw = System.getenv("PATCHER_KEYSTORE_PASSWORD")?.trim()
                storePassword = storePw
                keyAlias = System.getenv("PATCHER_KEY_ALIAS")?.trim()
                keyPassword = System.getenv("PATCHER_KEY_PASSWORD")?.trim()?.ifEmpty { null } ?: storePw
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += setOf("META-INF/versions/**", "META-INF/DEPENDENCIES", "META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.android.tools.build:apksig:8.13.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}
