plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "dev.solvit.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.solvit.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.7.0"
    }
    // setup-android can change ANDROID_USER_HOME. Never rely on AGP's implicit
    // debug-keystore path: CI restores and uses this exact file.
    signingConfigs.getByName("debug") {
        val stableKey = rootProject.file(".signing/debug.keystore")
        if (stableKey.exists()) {
            storeFile = stableKey
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":workspace-android"))
    implementation(project(":provider-openai"))
    implementation(project(":storage-sqlite"))
    implementation(project(":chat-runtime"))
    implementation(project(":release-updates"))
    implementation(project(":plugin-market"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.robolectric:robolectric:4.16.1")
}
