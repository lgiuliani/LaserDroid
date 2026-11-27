plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.laserdroid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.laserdroid"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":communication-api"))
    implementation(project(":communication-usb"))
    implementation(project(":controllers-api"))
    implementation(project(":controllers-grbl"))
    implementation(project(":core-machine"))
    implementation(project(":core-gcode"))
    implementation(project(":core-jobs"))
    implementation(project(":communication-network"))
    implementation(project(":core-remote"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
}
