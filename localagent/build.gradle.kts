// The Android local agent: the Mizar local kernel — the same TypeScript bundle
// the iOS app embeds — running on QuickJS, with the platform host bridged in
// through one small synchronous interface.
//
// A library on purpose: the app only gains a dependency on it, and the PoC
// instrumentation tests in this module install nothing but their own test APK.
// See README.md for the engine choice, the bundle-sync workflow and the 16 KB
// page caveat.

import java.io.File
import java.security.MessageDigest

plugins {
    // No version: the root build file already puts AGP on the build classpath
    // (it declares the application plugin with `apply false`), so requesting a
    // version here would fail with "already on the classpath with an unknown
    // version". Same reason `mizarkit` names its Kotlin plugin through the
    // catalog while its Android flavour is resolved from the classpath.
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "me.shirazu.dshmizar.localagent"
    compileSdk = 36

    // Pinned for the same reason the app pins it: no extra SDK download and no
    // dependence on whichever build-tools AGP considers current.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // A library's targetSdk defaults to its minSdk, which for the
    // self-instrumenting test APK means Android shows the "built for an older
    // version of Android" dialog over the PoC screenshot. `defaultConfig.targetSdk`
    // is deprecated for libraries; `testOptions.targetSdk` is the supported way
    // to pin the test APK's target.
    testOptions {
        targetSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The engine is not a dependency: QuickJS-NG 0.10.1 is vendored in
    // `src/main/cpp/quickjs` and the JNI bridge next to it is compiled into
    // `src/main/jniLibs/<abi>/libmizarquickjs.so` by
    // `scripts/build-quickjs.ps1`. The Gradle build only packages the prebuilt
    // libraries, so building the app never needs the NDK. See README.md
    // §Engine for why the published `quickjs-android` AARs could not be used.

    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// -- Keeping the kernel bundle in step with the iOS source of truth -----------
//
// The kernel is one TypeScript program (`iOS/LocalKernel/kernel/src`) with one
// generated bundle (`dist/mizar-kernel.js`), and the iOS app embeds exactly
// those bytes (`MizarKit/Sources/MizarKit/Kernel/KernelBundle.swift`). Android
// ships the same file as an asset. The copy here is committed so
// `:app:assembleDebug` works in an Android-only checkout; `syncKernelBundle`
// refreshes it from the iOS tree, `checkKernelBundle` proves the two are still
// the same bytes. Neither task is wired into `preBuild` — crossing repository
// boundaries must stay an explicit, reviewable step.

val iosKernelDist = rootProject.file("../iOS/LocalKernel/kernel/dist/mizar-kernel.js")
val kernelAsset = layout.projectDirectory.file("src/main/assets/mizar-kernel.js")

fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

tasks.register<Copy>("syncKernelBundle") {
    description = "Copy the iOS kernel bundle (dist/mizar-kernel.js) into this module's assets."
    group = "localagent"
    doFirst {
        require(iosKernelDist.isFile) {
            "syncKernelBundle: $iosKernelDist is missing. Run (cd iOS/LocalKernel/kernel && npm run build) first."
        }
    }
    from(iosKernelDist) {
        rename { "mizar-kernel.js" }
    }
    into(layout.projectDirectory.dir("src/main/assets"))
    doLast {
        logger.lifecycle("syncKernelBundle: copied ${iosKernelDist.length()} bytes -> $kernelAsset")
    }
}

tasks.register("checkKernelBundle") {
    description = "Verify the bundled kernel asset is byte-identical to the iOS dist bundle."
    group = "localagent"
    doLast {
        val asset = kernelAsset.asFile
        if (!iosKernelDist.isFile) {
            logger.lifecycle("checkKernelBundle: skipped — $iosKernelDist is not present in this checkout.")
            return@doLast
        }
        require(asset.isFile) { "checkKernelBundle: $asset is missing. Run :localagent:syncKernelBundle." }
        val expected = iosKernelDist.sha256()
        val actual = asset.sha256()
        require(expected == actual) {
            "checkKernelBundle: the bundled kernel is out of date.\n" +
                "  iOS dist: $expected (${iosKernelDist.length()} bytes)\n" +
                "  Android:  $actual (${asset.length()} bytes)\n" +
                "Run :localagent:syncKernelBundle, then re-run :localagent:checkKernelBundle."
        }
        logger.lifecycle("checkKernelBundle: OK - $actual (${asset.length()} bytes)")
    }
}
