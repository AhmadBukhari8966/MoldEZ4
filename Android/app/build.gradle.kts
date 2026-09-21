plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Use the existing desktop project credential unless the maintainer supplies an override.
// This is bundled in the APK; it is not a secret from someone who can inspect the app.
val desktopSource = rootProject.file("../MacOS/MacOS.py")
val bundledRoboflowKey = providers.environmentVariable("MOLDEZ_ROBOFLOW_API_KEY").orNull?.trim()
    ?: desktopSource.takeIf { it.isFile }?.readText()?.let {
        Regex("""(?m)^\s*ROBOFLOW_API_KEY\s*=\s*["']([^"'\r\n]+)["']""").find(it)?.groupValues?.get(1)
    }.orEmpty()
require(bundledRoboflowKey.isNotEmpty() && bundledRoboflowKey.length <= 512 && bundledRoboflowKey.all { it.code in 33..126 }) {
    "A project credential is required to build MoldEZ. Restore the desktop source or set MOLDEZ_ROBOFLOW_API_KEY."
}
val quotedRoboflowKey = "\"" + bundledRoboflowKey.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "edu.truman.moldez"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "edu.truman.moldez"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        buildConfigField("String", "ROBOFLOW_API_KEY", quotedRoboflowKey)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint { abortOnError = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.11.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.camera:camera-core:1.5.1")
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    implementation("androidx.camera:camera-view:1.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
