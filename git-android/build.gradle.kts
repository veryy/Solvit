import dev.solvit.buildtools.AndroidJgitTransform
import org.gradle.api.artifacts.type.ArtifactTypeDefinition

plugins { id("com.android.library") }
android {
    namespace = "dev.solvit.git"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}
val jgitAndroidEngine = configurations.create("jgitAndroidEngine") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "solvit-android-jgit")
}
dependencies {
    registerTransform(AndroidJgitTransform::class) {
        from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
        to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "solvit-android-jgit")
    }
    add(jgitAndroidEngine.name, "org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
    implementation(files(jgitAndroidEngine))
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    androidTestImplementation(project(":workspace-android"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
