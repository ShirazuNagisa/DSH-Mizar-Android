import java.util.Properties

// Release signing. Two sources, one shape: `keystore.properties` at the repo root for
// a local build, or the same four values in the environment for CI. When neither is
// present the release APK is simply unsigned rather than the build failing -- but any
// APK that leaves this machine goes through the signed path.
val signingValues = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    signingValues.getProperty(key) ?: System.getenv(env) ?: System.getenv(key)

val hasReleaseKeystore: Boolean = signingValue("storeFile", "MIZAR_KEYSTORE") != null
// The Android app: Compose UI, Firebase Messaging, notifications.
//
// The protocol itself lives in :mizarkit, which has no Android dependency at all.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "me.shirazu.dshmizar"
    compileSdk = 35

    // Pinned so the build does not depend on whichever build-tools version AGP
    // happens to consider current, and does not trigger an extra SDK download when
    // 35.0.0 is already installed.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "me.shirazu.dshmizar"
        minSdk = 26
        targetSdk = 35
        // Kept in step with the iOS target's MARKETING_VERSION/CURRENT_PROJECT_VERSION
        // (0.8.0 / 15) so a build can be identified from either platform's report.
        versionCode = 32
        versionName = "0.8.17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = rootProject.file(signingValue("storeFile", "MIZAR_KEYSTORE")!!)
                storePassword = signingValue("storePassword", "MIZAR_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "MIZAR_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "MIZAR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":mizarkit"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit4)
}

/**
 * Firebase is optional at build time.
 *
 * `google-services.json` carries a specific Firebase project, and this app has to
 * be buildable and usable without one: push is a feature, and an app that refuses
 * to compile until a project is provisioned is an app nobody can try. Without the
 * file the plugin is not applied, [me.shirazu.dshmizar.push.PushTokens] reports no
 * token, and every other screen works.
 */
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
