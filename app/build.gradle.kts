plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.reasonmachines.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.reasonmachines.android"
        minSdk = 26
        targetSdk = 34
        // CI passes -PversionCode/-PversionName so every release installs over the previous one.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0"
        buildConfigField("String", "START_URL", "\"https://reasonmachines.com/agents\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        // Stable key committed to this private repo so every build (local or CI) has the same
        // signature and new APKs install as updates. Override with env vars for a private key.
        create("release") {
            storeFile = file(System.getenv("REASON_KEYSTORE") ?: "reason.keystore")
            storePassword = System.getenv("REASON_KEYSTORE_PASSWORD") ?: "reasonandroid"
            keyAlias = System.getenv("REASON_KEY_ALIAS") ?: "reason"
            keyPassword = System.getenv("REASON_KEY_PASSWORD") ?: "reasonandroid"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.browser:browser:1.8.0")
}
