plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val identityDirectory = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR").orNull?.let(::file)
android {
    namespace = "com.shihab.diplay.legacy"
    compileSdk = 34
    ndkVersion = "25.2.9519653"
    defaultConfig {
        applicationId = "com.shihab.diplay.legacy"
        minSdk = 19
        targetSdk = 28
        versionCode = 6
        versionName = "0.1.5-wired-experimental"
        ndk { abiFilters += "armeabi-v7a" }
        externalNativeBuild { ndkBuild { arguments += "APP_PLATFORM=android-19" } }
        multiDexEnabled = true
    }
    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "1.8" }
    androidResources { noCompress += listOf("mp4", "m4a") } // MediaExtractor receives a resource fd on API 19.
    sourceSets {
        getByName("main") {
            java.srcDir(layout.buildDirectory.dir("generated/protocols"))
            identityDirectory?.let { assets.srcDir(it) }
        }
        getByName("test") { java.srcDir(layout.buildDirectory.dir("generated/protocol-tests")) }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2") }
    }
    lint {
        abortOnError = true
        // Sideloaded onto legacy head units; this project is not submitted to Google Play.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("androidx.multidex:multidex:2.0.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
}

val verifyIdentity by tasks.registering {
    doLast {
        val permitted = setOf("offline-mfi/identity.pk8", "offline-mfi/certificate.p7b")
        identityDirectory?.let { directory ->
            check(permitted.all { directory.resolve(it).let { file -> file.isFile && file.length() in 1..16384 } }) {
                "DIPLAY_AUTH_ASSETS_DIR must supply the two non-empty offline-mfi files"
            }
            check(directory.walkTopDown().filter { it.isFile }.all {
                it.relativeTo(directory).invariantSeparatorsPath in permitted
            }) { "The authentication asset input contains unexpected files" }
        }
        check(file("src/main/assets").walkTopDown().none { it.isFile }) {
            "Keep runtime authentication outside public source; use DIPLAY_AUTH_ASSETS_DIR"
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyIdentity) }
val requireStandaloneIdentity by tasks.registering {
    doLast { check(identityDirectory != null) { "Standalone CarPlay needs DIPLAY_AUTH_ASSETS_DIR" } }
}
tasks.register("assembleStandaloneDebug") { dependsOn(requireStandaloneIdentity, "assembleDebug") }
tasks.named("preBuild") { mustRunAfter(requireStandaloneIdentity) }

val stageProtocols by tasks.registering(Sync::class) {
    from("../../shared/src/main/java")
    include("com/shilapi/xcertplay/airplay/**", "com/shilapi/xcertplay/iap2/**")
    include("com/shilapi/xcertplay/transport/**")
    exclude("**/Ch341*.kt", "**/LinuxI2cTransport.kt", "**/IphoneUsbHost.kt",
        "**/IphoneCarPlayConfiguration.kt", "**/NcmFunctionDiscovery.kt", "**/NcmUsbBridge.kt",
        "**/BluetoothRfcommDuplexStream.kt", "**/Iap2WirelessControlClient.kt")
    include("com/shilapi/xcertplay/mfi/MfiAuthenticationClient.kt",
        "com/shilapi/xcertplay/mfi/LocalMfiAuthenticationClient.kt",
        "com/shilapi/xcertplay/mfi/Iap2MfiAuthenticationClient.kt")
    include("com/shilapi/xcertplay/media/MediaCodecSupport.kt", "com/shilapi/xcertplay/media/TouchLatencyProbe.kt",
        "com/shilapi/xcertplay/network/TcpLiveness.kt")
    into(layout.buildDirectory.dir("generated/protocols"))
}
tasks.named("preBuild") { dependsOn(stageProtocols) }

// Run the existing shared /info regression tests with the legacy protocol selection as well.
val stageProtocolTests by tasks.registering(Sync::class) {
    from("../../shared/src/test/java")
    include("com/shilapi/xcertplay/airplay/AirPlayInfoPlistTest.kt")
    into(layout.buildDirectory.dir("generated/protocol-tests"))
}
tasks.matching { it.name.contains("UnitTest") }.configureEach { dependsOn(stageProtocolTests) }
