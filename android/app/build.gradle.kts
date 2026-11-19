plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "fr.denizot.dictee"
    compileSdk = 35
    defaultConfig {
        applicationId = "fr.denizot.dictee.inline"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.1"
    }

    signingConfigs {
        create("ota") {
            val keystore = System.getenv("DICTEE_KEYSTORE_PATH")
            val password = System.getenv("DICTEE_SIGNING_PASSWORD")
            if (!keystore.isNullOrBlank() && !password.isNullOrBlank()) {
                storeFile = file(keystore)
                storePassword = password
                keyAlias = "dictee"
                keyPassword = password
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("ota")
            isMinifyEnabled = false
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core:1.15.0")
    testImplementation("junit:junit:4.13.2")
}
