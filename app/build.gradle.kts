plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.salutemesh"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.salutemesh"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "radio"
    productFlavors {
        create("mock") {
            dimension = "radio"
            isDefault = true
            applicationIdSuffix = ".mock"
            versionNameSuffix = "-mock"
        }
        create("mesh") {
            dimension = "radio"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":lib"))
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    add("meshImplementation", platform("org.meshtastic:sdk-bom:0.1.0"))
    add("meshImplementation", "org.meshtastic:sdk-core")
    add("meshImplementation", "org.meshtastic:sdk-storage-sqldelight")
    add("meshImplementation", "org.meshtastic:sdk-transport-ble")
    add("meshImplementation", "com.juul.kable:kable-core:0.44.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
