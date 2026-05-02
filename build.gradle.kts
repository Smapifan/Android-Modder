import java.util.Properties

plugins {
    id("com.android.application") version "8.5.2"
    kotlin("android") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

val storeFilePath = (keystoreProps.getProperty("storeFile") ?: System.getenv("ANDROID_STORE_FILE")).orEmpty()
val storePassword = keystoreProps.getProperty("storePassword") ?: System.getenv("ANDROID_STORE_PASSWORD")
val keyAlias = keystoreProps.getProperty("keyAlias") ?: System.getenv("ANDROID_KEY_ALIAS")
val keyPassword = keystoreProps.getProperty("keyPassword") ?: System.getenv("ANDROID_KEY_PASSWORD")
val appVersionCode = (System.getenv("ANDROID_VERSION_CODE") ?: "1").toIntOrNull() ?: 1
val appVersionName = System.getenv("ANDROID_VERSION_NAME") ?: "1.0"
val forceUnsignedRelease = (System.getenv("FORCE_UNSIGNED_RELEASE") ?: "0") == "1"
val signingReady = !forceUnsignedRelease &&
    storeFilePath.isNotBlank() &&
    !storePassword.isNullOrBlank() &&
    !keyAlias.isNullOrBlank() &&
    !keyPassword.isNullOrBlank()

android {
    namespace = "com.smapifan.androidmodder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.smapifan.androidmodder"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingReady) {
            create("release") {
                storeFile = file(storeFilePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingReady) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            if (signingReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.all {
            it.useJUnit()
        }
    }
}



androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("Android-Modder-${variant.buildType}-$appVersionName.apk")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
