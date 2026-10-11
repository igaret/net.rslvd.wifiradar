plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.rslvd.wifiradar.esp32"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.rslvd.wifiradar.esp32"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")   // replace with your own keystore for distribution
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    packaging { resources.excludes += setOf("META-INF/*.md", "META-INF/LICENSE*") }
}

dependencies {
    implementation(project(":csi-core"))
    // USB CDC/CP210x/CH34x/FTDI serial drivers for the OTG flasher
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
