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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation(project(":communication-api"))
    implementation("com.github.mik3y:usb-serial-for-android:3.12.0")
    implementation(libs.kotlinx.coroutines.core )
}

