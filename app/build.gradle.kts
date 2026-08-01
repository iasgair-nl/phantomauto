plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is driven entirely by environment variables so CI can inject a
// keystore without any secrets living in the repo. When RELEASE_KEYSTORE is not
// set (local dev, or CI without secrets configured) the release build simply
// stays unsigned instead of failing.
val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE")?.takeIf { it.isNotBlank() }
val releaseKeystoreFile = releaseKeystorePath?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "chat.phantomyard.auto"
    compileSdk = 35

    defaultConfig {
        applicationId = "chat.phantomyard.auto"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseKeystoreFile != null) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Just for CarConnection (detecting Android Auto engagement) - takes a plain
    // Context, not CarContext, so no CarAppService/Session scaffolding is needed.
    implementation("androidx.car.app:app:1.7.0")
    // Lightweight native relay listener: watches for incoming gift-wrapped DM events
    // while the WebView's own JS task queue is frozen (Chromium freezes background
    // pages' timers/tasks - confirmed via live CDP testing - so PhantomChat's own
    // relay pool can't process anything until the page is woken). This listener never
    // decrypts anything; it only detects that a matching event arrived.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
