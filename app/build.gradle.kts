import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from environment variables (set as GitHub Actions secrets in CI,
// or exported locally). Without them the release build falls back to the debug key.
val keystorePath: String? = System.getenv("ODOSYNC_KEYSTORE")
val hasReleaseKey = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "cc.adamu.odosync"
    compileSdk = 36

    defaultConfig {
        applicationId = "cc.adamu.odosync"
        minSdk = 31
        targetSdk = 36
        versionCode = (System.getenv("ODOSYNC_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("ODOSYNC_VERSION_NAME")
            ?: (project.findProperty("odosync.version") as String? ?: "0.0.0")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("ODOSYNC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ODOSYNC_KEY_ALIAS") ?: "odosync"
                keyPassword = System.getenv("ODOSYNC_KEY_PASSWORD") ?: System.getenv("ODOSYNC_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("screenshots.dir", rootProject.file("docs/screenshots").absolutePath)
            // ./gradlew :app:testDebugUnitTest -PrecordScreenshots  -> refresh docs/screenshots/*.png
            it.systemProperty("screenshots.record", project.hasProperty("recordScreenshots").toString())
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.12.4")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    // Only used to observe whether the phone is projecting to Android Auto (CarConnection).
    implementation("androidx.car.app:app:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM (android.jar only has stubs): drive summaries, HA payloads, DME profile.
    testImplementation("org.json:json:20260814")
    // Screen smoke tests: render every screen on the JVM (Robolectric native graphics) and save PNGs.
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
