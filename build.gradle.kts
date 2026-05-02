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
        // Only create the signing config when all credentials are present.
        // AGP 8.x auto-links any config named "release" to the release build type,
        // so an empty config would still cause NPE at package time.
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
            // Sign debug builds with the same JKS when credentials are available,
            // so both build types share one certificate and can upgrade each other.
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

// Rename APK outputs to "Android-Modder-{buildType}-{versionName}.apk" for easy identification.
// VariantOutput.outputFileName was removed from the AGP 8.x public API, so we rename on disk
// inside a doLast hook on each assemble task instead.
listOf("release", "debug").forEach { buildType ->
    val taskName = "assemble${buildType.replaceFirstChar { it.uppercaseChar() }}"
    tasks.configureEach {
        if (name == taskName) {
            doLast {
                val outDir = file("build/outputs/apk/$buildType")
                if (!outDir.isDirectory) return@doLast
                outDir.listFiles()
                    ?.filter { it.extension == "apk" }
                    ?.forEach { apk ->
                        val target = File(apk.parent, "Android-Modder-$buildType-$appVersionName.apk")
                        if (apk.absolutePath != target.absolutePath) {
                            if (!apk.renameTo(target)) {
                                logger.warn("[Android-Modder] Could not rename ${apk.name} → ${target.name}")
                            }
                        }
                    }
            }
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
