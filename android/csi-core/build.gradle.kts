plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.rslvd.wifiradar.core"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api("androidx.core:core-ktx:1.13.1")
    api("androidx.appcompat:appcompat:1.7.0")
    api("com.google.android.material:material:1.12.0")
    api("androidx.constraintlayout:constraintlayout:2.1.4")
    api("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    api("androidx.activity:activity-ktx:1.9.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
