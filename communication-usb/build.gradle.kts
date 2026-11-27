plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.laserdroid.communication.usb"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    implementation(project(":communication-api"))
    implementation(libs.usbserial.android)
    implementation(libs.kotlinx.coroutines.core)
}

