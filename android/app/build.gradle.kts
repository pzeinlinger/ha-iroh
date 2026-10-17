plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.ha_iroh.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ha_iroh.app"
        minSdk = 31
        targetSdk = 37
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.0.0-dev"
        manifestPlaceholders["appLabel"] = "ha-iroh"
    }
    // Signed only when KEYSTORE_PATH is set (CI); otherwise release is unsigned.
    val keystore = System.getenv("KEYSTORE_PATH")
    buildTypes.getByName("release") {
        if (keystore != null) signingConfig = signingConfigs.create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("KEYSTORE_PASS")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASS")
        }
    }
    // Installs next to the release app (own identity + pairing). ponytail: static shortcuts still target the release package.
    buildTypes.getByName("debug") {
        applicationIdSuffix = ".debug"
        manifestPlaceholders["appLabel"] = "ha-iroh debug"
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core") // not transitive of material3 anymore; BOM-managed
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk9:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.google.crypto.tink:tink-android:1.23.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.webkit:webkit:1.17.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
}
