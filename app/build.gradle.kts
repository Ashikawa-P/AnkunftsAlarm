plugins {
    id("com.android.application")
}

android {
    namespace = "de.gabriel.ankunftsalarm"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.gabriel.ankunftsalarm"
        minSdk = 33
        targetSdk = 36
        versionCode = 22
        versionName = "2.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
