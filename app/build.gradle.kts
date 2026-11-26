import java.io.FileInputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlinx.serizliation)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.aboutlibraries)
}

// The public half of the key that parsers downloaded by the official builds have to be signed with. Its other half is
// the PARSERS_SIGNING_KEY secret of the parsers repository. Left empty until that pair exists: then no parsers are
// taken from anywhere and the app keeps the ones it was built with. Whoever holds the private half can run code in
// every installed copy of the app, so generate it with `./gradlew generateParsersSigningKey -PkeyFile=...` in the
// parsers repository and keep the private file offline.
val parsersBundlePublicKey = ""

// Keep every Kotlin runtime artifact on the plugin's Kotlin version (single source: the catalog).
// The "detekt" tool configuration is skipped: detekt resolves its own embedded Kotlin compiler and
// breaks if the app's stdlib is forced onto it.
val kotlinVersion = libs.versions.kotlin.get()
configurations.matching { it.name != "detekt" }.configureEach {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
        force("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
        force("org.jetbrains.kotlin:kotlin-parcelize-runtime:$kotlinVersion")
        // Dagger/Hilt can ship an older kotlin-metadata-jvm that cannot read newer Kotlin class metadata
        // ("Provided Metadata instance has version 2.4.0, while maximum supported version is 2.3.0").
        force("org.jetbrains.kotlin:kotlin-metadata-jvm:$kotlinVersion")

        eachDependency {
            if (requested.group == "org.jetbrains.kotlin" &&
                (requested.name == "kotlin-stdlib-jdk7" || requested.name == "kotlin-stdlib-jdk8")
            ) {
                useTarget("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
            }
        }
    }
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
}

// Only 32-bit and 64-bit ARM are built and packaged (no x86 / x86_64).
val appAbis = listOf("armeabi-v7a", "arm64-v8a")
// -Pssiv.legacyJni=false stores native libs uncompressed (smaller install, larger APK; default true).

android {
    // Compose 1.13 requires compileSdk 37.1 (minor API level). targetSdk stays 37.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    namespace = "org.koitharu.kotatsu"

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "io.github.kotatsu.next"
        minSdk = 23
        targetSdk = 37
        ndk { abiFilters += appAbis }
        versionCode = 2046
        versionName = "9.10"

        testInstrumentationRunner = "org.koitharu.kotatsu.HiltTestRunner"

        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(FileInputStream(localPropertiesFile))
        }
        resValue("string", "tg_backup_bot_token", localProperties.getProperty("tg_backup_bot_token", ""))

        // Where parsers newer than the built-in ones are published, and the public half of the key they are signed
        // with. Override with `parsers_bundle_url` / `parsers_bundle_key` in local.properties or PARSERS_BUNDLE_URL /
        // PARSERS_BUNDLE_KEY in the environment; the key `none` takes no parsers from anywhere.
        fun parsersSetting(property: String, env: String): String? =
            localProperties.getProperty(property)?.takeIf { it.isNotBlank() }
                ?: System.getenv(env)?.takeIf { it.isNotBlank() }
        val parsersBundleUrl = parsersSetting("parsers_bundle_url", "PARSERS_BUNDLE_URL")
            ?: "https://github.com/Ero-gamer/kotatsu-next-parsers/releases/download/parsers-bundle"
        var parsersBundleKey = parsersSetting("parsers_bundle_key", "PARSERS_BUNDLE_KEY") ?: parsersBundlePublicKey
        if (parsersBundleKey == "none") {
            parsersBundleKey = ""
        }
        buildConfigField("String", "PARSERS_BUNDLE_URL", "\"$parsersBundleUrl\"")
        buildConfigField("String", "PARSERS_BUNDLE_KEY", "\"$parsersBundleKey\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("nightly") {
            initWith(getByName("release"))
            applicationIdSuffix = ".nightly"
        }
    }

    androidResources {
        generateLocaleConfig = false
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
        resValues = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = providers.gradleProperty("ssiv.legacyJni").orNull != "false"
        }
        resources {
            excludes +=
                listOf(
                    "META-INF/README.md",
                    "META-INF/NOTICE.md",
                )
        }
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        disable += listOf("MissingTranslation", "PrivateResource", "SetJavaScriptEnabled", "SimpleDateFormat")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions",
            "-opt-in=kotlin.ExperimentalStdlibApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi",
            "-opt-in=kotlinx.coroutines.InternalForInheritanceCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=coil3.annotation.InternalCoilApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            "-Xjspecify-annotations=strict",
            "-Xannotation-default-target=first-only",
            "-Xtype-enhancement-improvements-strict-mode",
        )
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

// Open source licenses screen (Settings > About). The plugin writes res/raw/aboutlibraries from the resolved
// dependency graph; config/aboutlibraries adds licences POMs lack (JitPack) and the native/Rust libraries and
// upstream projects that are not Gradle dependencies.
aboutLibraries {
    collect {
        configPath = rootProject.file("config/aboutlibraries")
    }
}

// Nightly builds get a date based version (variant API; applicationVariants is gone in AGP 9).
androidComponents {
    onVariants(selector().withBuildType("nightly")) { variant ->
        val now = LocalDateTime.now()
        variant.outputs.forEach { output ->
            output.versionCode.set(now.format(DateTimeFormatter.ofPattern("yyMMdd")).toInt())
            output.versionName.set("N" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd")))
        }
    }
}

dependencies {
    var parsersVersion = libs.versions.parsers.get()
    if (System.getProperties().containsKey("parsersVersionOverride")) {
        parsersVersion = System.getProperty("parsersVersionOverride")
    }

    implementation("com.github.Ero-gamer:kotatsu-next-parsers:$parsersVersion") {
        exclude(group = "org.json", module = "json")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
    }

    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.activity)
    implementation(libs.aboutlibraries.compose.m3)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.transition)
    implementation(libs.androidx.collection)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.process)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.biometric)
    implementation(libs.material)
    implementation(libs.mmkv)
    implementation(libs.androidx.lifecycle.common.java8)
    implementation(libs.androidx.webkit)

    implementation(libs.androidx.work.runtime)
    implementation(libs.guava)
    implementation(libs.androidx.window)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.okhttp.tls)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.okio)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.adapterdelegates)
    implementation(libs.adapterdelegates.viewbinding)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.coil.core)
    implementation(libs.coil.network)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.avif.decoder)
    implementation(libs.ssiv) {
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation(libs.disk.lru.cache)
    implementation(libs.markwon)
    implementation(libs.kizzyrpc) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-android-extensions-runtime")
    }

    implementation(libs.acra.http)
    implementation(libs.acra.dialog)
    implementation(libs.conscrypt.android)

    debugImplementation(libs.leakcanary.android)
    "nightlyImplementation"(libs.leakcanary.android)
    debugImplementation(libs.workinspector)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.moshi.kotlin)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
}

// ── Rust codecs (JPEG XL + animated PNG) ──────────────────────────────────────
// Builds libssivcodecs.so per ABI with cargo. Entirely optional: when cargo / the NDK / the Rust
// target are unavailable, or the build fails, a warning is printed and the APK is built without the
// library (the app then uses the system decoders). Disable with -Pssiv.rust=false.
val rustCrateDir = file("src/main/rust/ssivcodecs")
val rustOutDir = layout.buildDirectory.dir("rustJniLibs")
val rustTargetDir = layout.buildDirectory.dir("rustTarget")
// (ABI, Rust triple, NDK clang triple, library name). armeabi-v7a is built twice: the plain target has
// NEON disabled (safe everywhere); thumbv7neon enables it and is loaded only on CPUs that report NEON
// (see NativeCodecs). arm64-v8a has NEON in the baseline, so one library.
val rustAbis =
    listOf(
        listOf("arm64-v8a", "aarch64-linux-android", "aarch64-linux-android", "ssivcodecs"),
        listOf("armeabi-v7a", "armv7-linux-androideabi", "armv7a-linux-androideabi", "ssivcodecs"),
        listOf("armeabi-v7a", "thumbv7neon-linux-androideabi", "armv7a-linux-androideabi", "ssivcodecs_neon"),
    )
val rustMinSdk = 23
val rustEnabled = providers.gradleProperty("ssiv.rust").orNull != "false"
val ndkFromSdk = androidComponents.sdkComponents.ndkDirectory

val rustTasks =
    rustAbis.map { (abi, rustTriple, clangTriple, libName) ->
        tasks.register("buildRust${abi.replace("-", "").replaceFirstChar { it.uppercase() }}${if (libName.endsWith("_neon")) "Neon" else ""}") {
            group = "build"
            description = "Builds lib$libName.so for $abi ($rustTriple)"
            onlyIf { rustEnabled }
            inputs.dir(rustCrateDir.resolve("src"))
            inputs.file(rustCrateDir.resolve("Cargo.toml"))
            inputs.file(rustCrateDir.resolve("build.rs"))
            inputs.dir(rustCrateDir.resolve("vendor"))
            outputs.file(rustOutDir.map { it.dir(abi).file("lib$libName.so") })
            doLast {
                fun skip(reason: String) = logger.warn("Rust codecs ($abi/$rustTriple) skipped: $reason")
                try {
                    val home = System.getProperty("user.home")
                    val cargo =
                        sequenceOf(File(home, ".cargo/bin/cargo"), File("/usr/local/cargo/bin/cargo"))
                            .firstOrNull { it.canExecute() }
                            ?.absolutePath ?: "cargo"
                    val ndk =
                        (System.getenv("ANDROID_NDK_ROOT") ?: System.getenv("ANDROID_NDK_HOME"))
                            ?.let(::File)
                            ?.takeIf { it.isDirectory }
                            ?: runCatching { ndkFromSdk.get().asFile }.getOrNull()?.takeIf { it.isDirectory }
                    if (ndk == null) return@doLast skip("Android NDK not found")
                    val bin =
                        File(ndk, "toolchains/llvm/prebuilt")
                            .listFiles()
                            ?.firstOrNull { it.isDirectory }
                            ?.resolve("bin")
                    val linker = bin?.resolve("$clangTriple$rustMinSdk-clang")
                    if (linker == null || !linker.canExecute()) return@doLast skip("NDK linker not found")

                    fun run(
                        vararg cmd: String,
                        env: Map<String, String> = emptyMap(),
                    ): Int {
                        val pb = ProcessBuilder(*cmd).directory(rustCrateDir).redirectErrorStream(true)
                        pb.environment().putAll(env)
                        val proc = pb.start()
                        val out = proc.inputStream.bufferedReader().readText()
                        val code = proc.waitFor()
                        if (code != 0) logger.warn(out.takeLast(4000))
                        return code
                    }
                    // Idempotent and quick when the target is already installed.
                    runCatching { run("rustup", "target", "add", rustTriple) }
                    val envKey = rustTriple.uppercase().replace('-', '_')
                    val ccKey = rustTriple.replace('-', '_')
                    val code =
                        run(
                            cargo,
                            "build",
                            "--release",
                            "--locked".takeIf { rustCrateDir.resolve("Cargo.lock").exists() } ?: "--quiet",
                            "--target",
                            rustTriple,
                            env =
                                mapOf(
                                    "CARGO_TARGET_DIR" to rustTargetDir.get().asFile.absolutePath,
                                    "CARGO_TARGET_${envKey}_LINKER" to linker.absolutePath,
                                    // The `cc` crate compiles the vendored libwebp with the NDK clang for this target.
                                    "CC_$ccKey" to linker.absolutePath,
                                    "AR_$ccKey" to (bin?.resolve("llvm-ar")?.absolutePath ?: "llvm-ar"),
                                    // 16 KB page-size aligned segments (Android 15+).
                                    "RUSTFLAGS" to "-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,--gc-sections -C link-arg=-Wl,--icf=safe",
                                ),
                        )
                    val built = rustTargetDir.get().asFile.resolve("$rustTriple/release/libssivcodecs.so")
                    if (code != 0 || !built.isFile) return@doLast skip("cargo build failed")
                    val dest =
                        rustOutDir
                            .get()
                            .asFile
                            .resolve(abi)
                            .also { it.mkdirs() }
                    built.copyTo(dest.resolve("lib$libName.so"), overwrite = true)
                } catch (e: Exception) {
                    skip(e.toString())
                }
            }
        }
    }

// A static directory (not the Provider): AGP 9 rejects Providers in the SourceSet API. The merge*JniLibFolders
// dependency below orders the cargo tasks before the merge.
android.sourceSets
    .getByName("main")
    .jniLibs
    .directories
    .add(rustOutDir.get().asFile.absolutePath)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(rustTasks)
}
