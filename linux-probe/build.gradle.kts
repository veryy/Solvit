// Remote compatibility probe only: no app dependency or shipped UI.
plugins { id("com.android.application") }
android {
    namespace = "dev.solvit.linuxprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.solvit.linuxprobe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "probe"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libsolvit_loader.so" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":linux-runtime"))
    implementation(project(":linux-android"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
