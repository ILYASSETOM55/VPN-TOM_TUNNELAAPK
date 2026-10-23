plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.tomtunnel.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.tomtunnel.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "1.3.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.github.mwiede:jsch:0.2.18")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}