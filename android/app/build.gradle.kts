plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "fr.denizot.dictee"
    compileSdk = 35
    defaultConfig {
        applicationId = "fr.denizot.dictee.inline"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies { implementation("androidx.core:core:1.15.0") }
