plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystoreFile = providers.environmentVariable("ANDROID_RELEASE_KEYSTORE_FILE").orNull
    ?.takeIf(String::isNotBlank)
val releaseKeystorePassword = providers.environmentVariable("ANDROID_RELEASE_KEYSTORE_PASSWORD").orNull
    ?.takeIf(String::isNotBlank)
val releaseKeyAlias = providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS").orNull
    ?.takeIf(String::isNotBlank)
val releaseKeyPassword = providers.environmentVariable("ANDROID_RELEASE_KEY_PASSWORD").orNull
    ?.takeIf(String::isNotBlank)
val releaseSigningValues = listOf(
    releaseKeystoreFile,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val releaseSigningConfigured = releaseSigningValues.all { it != null }

check(releaseSigningValues.all { it == null } || releaseSigningConfigured) {
    "Release signing requires all ANDROID_RELEASE_* environment variables."
}

android {
    namespace = "app.quickerlink"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.quickerlink"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.2.0-alpha.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystoreFile))
                storePassword = requireNotNull(releaseKeystorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        disable += "NewerVersionAvailable"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.google.zxing:core:3.5.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
