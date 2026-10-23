import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.androidApplication)
}
repositories {
    google()
    mavenCentral()
    maven {
        url = uri("https://raw.githubusercontent.com/rustls/rustls-platform-verifier/maven-archive/android-release-support/maven/")
        content { includeGroup("org.rustls") }
    }
}

// One version for the whole installer: the workspace version in ../../Cargo.toml (desktop's too), so a release
// carries the same number on every platform and the self-update compares like with like.
val installerVersion: String = Regex("""(?m)^\[workspace\.package\]\s*\nversion\s*=\s*"([^"]+)"""")
    .find(rootDir.resolve("../Cargo.toml").readText().replace("\r\n", "\n"))?.groupValues?.get(1)
    ?: error("No [workspace.package] version in Cargo.toml")

/** 3.1.0-alpha.2 -> 3_01_00_02: pre-releases sort before their release (alpha 0-29, beta 30-59, rc 60-89, final 99). */
fun versionCodeOf(version: String): Int {
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?$""").find(version)
        ?: error("Unsupported installer version $version")
    val (major, minor, patch, stage, number) = match.destructured
    val pre = when (stage) {
        "alpha" -> number.toInt()
        "beta" -> 30 + number.toInt()
        "rc" -> 60 + number.toInt()
        else -> 99
    }
    check(pre <= 99 && minor.toInt() < 100 && patch.toInt() < 100) { "Version $version doesn't fit the version code" }
    return major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + pre
}

android {
    // The Java package stays com.nuvio.usbprobe: the native library's JNI entry points are named after it.
    namespace = "com.nuvio.usbprobe"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.android.compileSdkMinor.get().toInt()
    defaultConfig {
        applicationId = "com.nuvio.z.installer"
        minSdk = 24
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = versionCodeOf(installerVersion)
        versionName = installerVersion
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Release builds are signed with the installer's own key, from CI secrets. Every update must carry the same
    // key, or Android refuses it and the self-update cannot install it.
    val keystore = System.getenv("NUVIOZ_ANDROID_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("NUVIOZ_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NUVIOZ_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("NUVIOZ_ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            // A development build installs beside the released app instead of clashing with its signature.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

abstract class BuildInstallerRust @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeSources: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val buildScripts: ConfigurableFileCollection
    @get:Internal abstract val scriptDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun build() {
        val out = outputDirectory.get().asFile.absolutePath
        val scripts = scriptDirectory.get().asFile
        execOperations.exec {
            if (System.getProperty("os.name").startsWith("Windows")) {
                commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                    scripts.resolve("build-native.ps1").absolutePath, "-Arm64Only", "-OutputDirectory", out)
            } else {
                commandLine("bash", scripts.resolve("build-native.sh").absolutePath, out)
            }
        }
    }
}
val buildInstallerRust = tasks.register<BuildInstallerRust>("buildInstallerRust") {
    nativeSources.from(fileTree(projectDir.resolve("../native")) { exclude("build/**", "target/**") })
    buildScripts.from(projectDir.resolve("../build-native.ps1"), projectDir.resolve("../build-native.sh"))
    scriptDirectory.set(projectDir.resolve(".."))
    outputDirectory.set(layout.buildDirectory.dir("generated/jniLibs"))
}
// The installer's screens are desktop's own ui/, copied at build time (never a second copy in git), with
// android-bridge.js loaded before the shared scripts so they find window.__TAURI__ as on desktop.
abstract class CopyInstallerUi : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val ui: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val bridge: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun copy() {
        val out = outputDirectory.get().asFile.resolve("ui")
        out.deleteRecursively()
        ui.get().asFile.copyRecursively(out)
        bridge.get().asFile.copyTo(out.resolve("android-bridge.js"))
        val index = out.resolve("index.html")
        val marker = "<script src=\"icons.js\"></script>"
        val html = index.readText()
        check(html.contains(marker)) { "ui/index.html no longer loads icons.js first; update CopyInstallerUi" }
        index.writeText(html.replace(marker, "<script src=\"android-bridge.js\"></script>\n  $marker"))
    }
}
val copyInstallerUi = tasks.register<CopyInstallerUi>("copyInstallerUi") {
    ui.set(projectDir.resolve("../../ui"))
    bridge.set(projectDir.resolve("src/main/web/android-bridge.js"))
    outputDirectory.set(layout.buildDirectory.dir("generated/installerUi"))
}
androidComponents.onVariants(androidComponents.selector().all()) {
    it.sources.jniLibs?.addGeneratedSourceDirectory(buildInstallerRust, BuildInstallerRust::outputDirectory)
    it.sources.assets?.addGeneratedSourceDirectory(copyInstallerUi, CopyInstallerUi::outputDirectory)
}

dependencies {
    // Matches rustls-platform-verifier-android 0.2.0 in native/Cargo.lock.
    implementation("org.rustls:rustls-platform-verifier:0.2.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation(libs.androidx.testExt.junit)
}
