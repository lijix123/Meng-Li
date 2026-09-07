plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.liguang.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.liguang.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 41
        versionName = "0.1.40"
    }

    signingConfigs {
        create("release") {
            storeFile = file(providers.gradleProperty("liguangStoreFile").get())
            storePassword = providers.gradleProperty("liguangStorePassword").get()
            keyAlias = providers.gradleProperty("liguangKeyAlias").get()
            keyPassword = providers.gradleProperty("liguangKeyPassword").get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
