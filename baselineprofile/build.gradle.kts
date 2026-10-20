plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "dev.boottv.baselineprofile"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Numbers from the emulator are only good for before/after comparisons; the real check is on the stick.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }
    targetProjectPath = ":app"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.3")
}
