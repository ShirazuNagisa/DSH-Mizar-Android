// Compile the protocol layer against Android's own `android.jar` instead of the
// JDK, so that a method Android does not have fails the build instead of the app.
//
// Why this module exists: `mizarkit` is a plain Kotlin/JVM module compiled by the
// JDK 17 toolchain, and Android lint never looks at a JVM module. `URLDecoder
// .decode(String, Charset)` is a Java 10 addition that Android only gained in
// API 33; the compiler was happy, the tests were happy, and every device below
// Android 13 threw NoSuchMethodError the moment a pairing link was decoded.
// Compiling the same sources against `platforms/android-26/android.jar` -- the
// jar for minSdk -- makes that a compile error.
//
// It compiles the sources a second time and produces nothing anyone ships: the
// jar task is off, there are no tests here, and the only output is the compile
// result. Nothing else in the build depends on this module.
//
//   ./gradlew :mizarkit-android-api:androidApiCheck
//
// The jar is taken from `-PandroidApiJar=<path>`, else from `sdk.dir` in
// local.properties, else from ANDROID_HOME/ANDROID_SDK_ROOT, at
// `platforms/android-<androidApiLevel>/android.jar` (default 26, the minSdk).

// `java` is the Gradle extension in this script, so the JDK type needs a name.
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

/**
 * The `android.jar` to compile against, resolved lazily so that a machine without
 * the platform only fails when this check actually runs -- not on every build.
 */
val androidJar: Provider<File> = providers.provider {
    val explicit = (findProperty("androidApiJar") as String?)?.let(::File)
    if (explicit != null) {
        require(explicit.isFile) { "androidApiJar points at ${explicit.absolutePath}, which is not a file" }
        return@provider explicit
    }

    val level = (findProperty("androidApiLevel") as String?) ?: "26"
    val sdk = sequenceOf(
        rootProject.file("local.properties")
            .takeIf { it.isFile }
            ?.let { file ->
                Properties().apply { file.inputStream().use { load(it) } }.getProperty("sdk.dir")
            },
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
    ).filterNotNull().map(::File).firstOrNull { it.isDirectory }

    val jar = sdk?.resolve("platforms/android-$level/android.jar")
    require(jar != null && jar.isFile) {
        buildString {
            append("No android.jar for API $level.\n")
            append("Looked for: ${sdk?.resolve("platforms/android-$level/android.jar") ?: "<no SDK found>"}\n")
            append("Install it: sdkmanager \"platforms;android-$level\"\n")
            append("Or point at a jar: ./gradlew :mizarkit-android-api:androidApiCheck -PandroidApiJar=<path>")
        }
    }
    jar
}

sourceSets["main"].kotlin.setSrcDirs(listOf(rootProject.file("mizarkit/src/main/kotlin")))

dependencies {
    // Same surface the real module compiles against, minus the JDK: without these
    // the sources would fail on unresolved references before reaching the API
    // question this module exists to ask.
    compileOnly(files(androidJar))
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.okhttp)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        // The whole point: do not put the JDK on the classpath, so `java.*` comes
        // from android.jar alone. Kotlin 2.x spells it `-no-jdk`; the old
        // `-Xno-jdk` is silently ignored, which turns this module into a no-op
        // that still prints BUILD SUCCESSFUL.
        freeCompilerArgs.add("-no-jdk")
    }
}

// Nothing here is shipped or tested; the compile is the whole check.
tasks.jar { enabled = false }

tasks.register("androidApiCheck") {
    group = "verification"
    description = "Compile mizarkit against android.jar (API 26 by default) to catch JDK-only methods."
    dependsOn(tasks.named("compileKotlin"))
}
