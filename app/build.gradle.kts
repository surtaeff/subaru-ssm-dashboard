import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "kg.forester.sg"
    compileSdk = 34

    defaultConfig {
        applicationId = "kg.forester.sg"
        minSdk = 23
        targetSdk = 34
        versionCode = 42
        versionName = "1.0-beta2"
    }

    // head units and phones are 32 or 64-bit ARM; the other copies of the native libraries only made the APK bigger
    packaging { jniLibs { excludes += listOf("lib/x86/**", "lib/x86_64/**", "lib/armeabi/**", "lib/mips/**", "lib/mips64/**") } }

    // A release also gets one APK per ABI, for Updater.pickApk. The universal APK stays the main file under its old name:
    // installs older than that function only look for it. Debug builds make the universal one alone.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release") }
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    // the SensorBox sketch ships inside the app (assets/sensorbox/sensorbox.ino): saved from Настройки → «Доп датчики»
    sourceSets["main"].assets.srcDir("../hardware")
    // a local arduino-cli compile leaves build/ (elf, map: tens of MB) next to the sketches: never into the APK
    androidResources.ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:<dir>build"

    // Release key lives outside the repo: SUBARU_RELEASE_STORE / _PASSWORD / _ALIAS in ~/.gradle/gradle.properties.
    // Without it (e.g. someone building from source) the debug key is used.
    val releaseStore = (project.findProperty("SUBARU_RELEASE_STORE") as String?)?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = project.property("SUBARU_RELEASE_PASSWORD") as String
            keyAlias = project.property("SUBARU_RELEASE_ALIAS") as String
            keyPassword = project.property("SUBARU_RELEASE_PASSWORD") as String
        }
    }

    // minSdk 23 is the budget head units of Android 6 (AC8227L and the like, often shown as "9.1"): a call above it compiles and crashes there, and no JVM test or new emulator
    // shows it. NewApi is fatal, so every release build (lintVitalRelease) stops on one; the other checks are left as they were.
    // abortOnError stays on: with it off a fatal issue no longer stops the release build either.
    lint {
        fatal += "NewApi"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        compose = true
    }
}

// The APK is named after the app: carpulse-0.9-beta21.apk (release and debug alike), carpulse-0.9-beta21-arm64-v8a.apk
// for one ABI; applicationId and the key stay, so it installs over the versions that were called Subaru Dashboard.
android.applicationVariants.all {
    val ver = versionName
    outputs.all {
        val out = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
        val abi = out.getFilter("ABI")
        out.outputFileName = if (abi == null) "carpulse-$ver.apk" else "carpulse-$ver-$abi.apk"
    }
}

// R8 overwrites mapping.txt each time: the copies of its last 3 runs stay beside it, to read a stack trace from such a
// build's log (76 MB each). Named by the run itself: a build that didn't run R8 adds none.
val keepMapping by tasks.registering(Copy::class) {
    val mapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
    val kept = layout.buildDirectory.dir("outputs/mapping/builds")
    from(mapping)
    into(kept)
    outputs.upToDateWhen { false } // the trim below has to run even when there's nothing new to copy
    rename { "mapping-${android.defaultConfig.versionName}-${SimpleDateFormat("yyyyMMdd-HHmmss").format(Date(mapping.get().asFile.lastModified()))}.txt" }
    doLast {
        kept.get().asFile.listFiles { f -> f.name.startsWith("mapping-") }.orEmpty()
            .sortedBy { it.name.takeLast(19) }.dropLast(3).forEach { it.delete() }
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(keepMapping) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    // installs the baseline profile (src/main/baseline-prof.txt) on units without a store to do it
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    debugImplementation("androidx.metrics:metrics-performance:1.0.0-beta01")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    implementation("com.composables:icons-lucide:1.1.0")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // the Media screen's own player: files from a USB stick, SD card or memory, with a media session
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1") // some internet stations stream HLS (.m3u8)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // offline speech synthesis for the warnings (k2-fsa sherpa-onnx, Apache-2.0; ARM-only copy of the v1.13.8 AAR)
    implementation(files("libs/sherpa-onnx-1.13.8-arm.aar"))
    // unpacking the downloaded voice (.tar.bz2)
    implementation("org.apache.commons:commons-compress:1.21")
    // the QR code of the Telegram group in Настройки, Связь (encoder only, no camera)
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    // the real org.json in unit tests (Android's is a stub there): the SensorBox catalogue is parsed with it
    testImplementation("org.json:json:20240303")
    // a real XmlPullParser in unit tests (Android's is a stub there): own RomRaider definitions are read with it
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
