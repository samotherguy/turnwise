import java.util.Properties

// Your own addresses live in local.properties (not committed): turnwise.url=https://… and waker.url=https://…
val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
fun prop(key: String, default: String) = (local.getProperty(key) ?: System.getenv(key.uppercase().replace(".", "_")) ?: default).trim().trimEnd('/')

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.rollingrecorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rollingrecorder"
        buildConfigField("String", "TURNWISE_URL", "\"${prop("turnwise.url", "https://your-box.your-tailnet.ts.net:8446")}\"")
        buildConfigField("String", "WAKER_URL", "\"${prop("waker.url", "https://waker.your-tailnet.ts.net:8491")}\"")
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["appName"] = "Turnwise"
        ndk { abiFilters += listOf("arm64-v8a") }     // the bundled llama.cpp server is arm64 only
    }
    packaging { jniLibs { useLegacyPackaging = true } }   // extract jniLibs to disk: the server binary is executed, not loaded

    // "full" is the app with everything, including the call-capture experiment (an accessibility service, which Play policy
    // forbids and Play Protect refuses to sideload). "play" is the same app without it, for a store build. "demo" installs
    // beside the real app (own package name) and talks to the server's demo account, so nothing real is shown.
    flavorDimensions += "account"
    productFlavors {
        create("full") { dimension = "account"; buildConfigField("boolean", "CALL_CAPTURE", "true") }
        create("play") { dimension = "account"; buildConfigField("boolean", "CALL_CAPTURE", "false") }
        create("demo") {
            dimension = "account"
            buildConfigField("boolean", "CALL_CAPTURE", "false")
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            manifestPlaceholders["appName"] = "Turnwise Demo"
            buildConfigField("String", "TURNWISE_URL", "\"${prop("turnwise.demo.url", prop("turnwise.url", "https://your-box.your-tailnet.ts.net:8446").replace(":8446", ":8448"))}\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material:material-icons-extended")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // android.jar stubs org.json in local tests
}
