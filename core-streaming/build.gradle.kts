plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
}
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":core-gcode"))
    implementation(project(":controllers-api"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Integration tests
    testImplementation(project(":controllers-grbl"))
    testImplementation(project(":communication-api"))
    testImplementation(project(":simulator"))

    testImplementation(kotlin("test"))
}
tasks.test {
    useJUnitPlatform()
}