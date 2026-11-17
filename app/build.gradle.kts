import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.local.voicenotes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.local.voicenotes.litert"
        minSdk = 31
        targetSdk = 35
        versionCode = 8
        versionName = "0.6.1"
        ndk { abiFilters += "arm64-v8a" }

        testInstrumentationRunner = "com.local.voicenotes.audio.AudioDecoderChecks"
    }

    buildFeatures { compose = true }

    // Bundled .tflite graphs stay uncompressed so LiteRT can map them straight from the APK.
    androidResources { noCompress += "tflite" }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }

    buildTypes {
        release {
            // No separate release key yet: signed with the debug key so it upgrades the
            // debug builds already installed (a new key would force an uninstall and data loss).
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Stack-trace mapping for Compose groups only aids debugging of minified builds; its extra
// tool is not needed to ship.
composeCompiler {
    includeComposeMappingFile.set(false)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    // LiteRT pulls in fragment 1.1.0 transitively; ActivityResult APIs need >= 1.3.0.
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.google.ai.edge.litert:litert:2.1.6")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // The debug app already resolves these versions via ui-tooling; pin them for every variant so
    // release ships exactly what debug was tested with (and builds from the same offline cache).
    implementation("androidx.compose.foundation:foundation:1.9.0")
    implementation("androidx.compose.animation:animation:1.9.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Functional tests stay independent of host speed; run this probe explicitly when tuning.
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest" || name == "testReleaseUnitTest") {
        filter.excludeTestsMatching("com.local.voicenotes.audio.StreamingResamplerBenchmark")
    }
}
tasks.register<Test>("resamplerBenchmark") {
    group = "verification"
    description = "Optional 15-minute resampler host timing probe"
    dependsOn("testDebugUnitTest")
    val unit = tasks.named<Test>("testDebugUnitTest").get()
    testClassesDirs = unit.testClassesDirs
    classpath = unit.classpath
    filter.includeTestsMatching("com.local.voicenotes.audio.StreamingResamplerBenchmark")
    testLogging.showStandardStreams = true
}
