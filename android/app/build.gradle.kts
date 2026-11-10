plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Single source of truth for the loader: the same file the Windows/CLI build embeds.
val copyLoader by tasks.registering(Copy::class) {
    from(rootProject.file("../src/Enabler.Core/discover-web-loader.js"))
    into(layout.buildDirectory.dir("generated/loader"))
}

android {
    namespace = "nl.mrmuiz.discoverenabler"
    compileSdk = 34

    defaultConfig {
        applicationId = "nl.mrmuiz.discoverenabler"
        minSdk = 21
        targetSdk = 34
        versionCode = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("APP_VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        val ks = System.getenv("ANDROID_KEYSTORE_FILE")
        if (ks != null) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without a release keystore (env vars) fall back to the debug key so the APK is still installable.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/loader"))

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("META-INF/versions/**", "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }
}

tasks.named("preBuild") { dependsOn(copyLoader) }

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("com.android.tools.build:apksig:8.5.2")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    testImplementation("junit:junit:4.13.2")
}
