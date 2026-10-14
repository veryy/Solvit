plugins { id("com.android.library") }
android {
    namespace = "dev.solvit.workspace"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig { minSdk = 26; ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") } }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":system-runtime"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.robolectric:robolectric:4.16.1")
}
