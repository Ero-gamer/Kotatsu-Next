import java.io.FileInputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.ksp)
    id("kotlin-parcelize")
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlinx.serizliation)
    alias(libs.plugins.compose.compiler)
}

// Keep every Kotlin runtime artifact on the plugin's Kotlin version (single source: the catalog).
// The "detekt" tool configuration is skipped: detekt resolves its own embedded Kotlin compiler and
// breaks if the app's stdlib is forced onto it.
val kotlinVersion = libs.versions.kotlin.get()
configurations.matching { it.name != "detekt" }.configureEach {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
        force("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
        force("org.jetbrains.kotlin:kotlin-parcelize-runtime:$kotlinVersion")
        // Dagger/Hilt 2.58 ships kotlin-metadata-jvm 2.3.0, which cannot read Kotlin 2.4 class metadata
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

android {
    compileSdk = 37
    namespace = "org.koitharu.kotatsu"

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "io.github.kotatsu.next"
        minSdk = 23
        targetSdk = 37
        versionCode = 2046
        versionName = "9.10"

        testInstrumentationRunner = "org.koitharu.kotatsu.HiltTestRunner"

        ksp {
            arg("room.generateKotlin", "true")
        }

        androidResources {
            generateLocaleConfig = false
        }

        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(FileInputStream(localPropertiesFile))
        }
        resValue("string", "tg_backup_bot_token", localProperties.getProperty("tg_backup_bot_token", ""))
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
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
        getByName("androidTest").assets.srcDirs(files("$projectDir/schemas"))
        getByName("main").java.srcDirs("src/main/kotlin/")
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
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

    lint {
        abortOnError = true
        disable += listOf("MissingTranslation", "PrivateResource", "SetJavaScriptEnabled", "SimpleDateFormat")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = false
    }

    applicationVariants.configureEach {
        if (name == "nightly") {
            outputs.configureEach {
                val output = this as? com.android.build.gradle.internal.api.ApkVariantOutputImpl
                val now = LocalDateTime.now()
                output?.versionCodeOverride = now.format(DateTimeFormatter.ofPattern("yyMMdd")).toInt()
                output?.versionNameOverride = "N" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            }
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

    // The Compose BOM transitively pulls in androidx.lifecycle:lifecycle-runtime-compose(-android),
    // an artifact this app never calls directly (no collectAsStateWithLifecycle usage etc).
    // Versions >= 2.11.0 declare an AAR-metadata requirement of AGP >= 9.1.0, which fails the
    // build outright, and no BOM version was found that avoids pulling >= 2.11.0 in (stepping
    // the BOM back to 2026.05.01 was tried and still resolved 2.11.0). Capping the transitive
    // version directly via a constraint is deterministic regardless of what any BOM requests -
    // "strictly" is required here because a plain version request would lose to the BOM's
    // preferred version under Gradle's default highest-wins conflict resolution.
    constraints {
        implementation("androidx.lifecycle:lifecycle-runtime-compose-android") {
            version { strictly("2.10.0") }
            because("2.11.0+ requires AGP >= 9.1.0; this build is pinned to AGP 8.13.0")
        }
        implementation("androidx.lifecycle:lifecycle-runtime-compose") {
            version { strictly("2.10.0") }
            because("2.11.0+ requires AGP >= 9.1.0; this build is pinned to AGP 8.13.0")
        }
    }

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)
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
val rustAbis = mapOf(
    "arm64-v8a" to ("aarch64-linux-android" to "aarch64-linux-android"),
    "armeabi-v7a" to ("armv7-linux-androideabi" to "armv7a-linux-androideabi"),
    "x86_64" to ("x86_64-linux-android" to "x86_64-linux-android"),
)
val rustMinSdk = 23
val rustEnabled = providers.gradleProperty("ssiv.rust").orNull != "false"
val ndkFromSdk = androidComponents.sdkComponents.ndkDirectory

val rustTasks = rustAbis.map { (abi, triples) ->
    val (rustTriple, clangTriple) = triples
    tasks.register("buildRust${abi.replace("-", "").replaceFirstChar { it.uppercase() }}") {
        group = "build"
        description = "Builds libssivcodecs.so for $abi"
        onlyIf { rustEnabled }
        inputs.dir(rustCrateDir.resolve("src"))
        inputs.file(rustCrateDir.resolve("Cargo.toml"))
        inputs.file(rustCrateDir.resolve("build.rs"))
        outputs.dir(rustOutDir.map { it.dir(abi) })
        doLast {
            fun skip(reason: String) = logger.warn("Rust codecs ($abi) skipped: $reason")
            try {
                val home = System.getProperty("user.home")
                val cargo = sequenceOf(File(home, ".cargo/bin/cargo"), File("/usr/local/cargo/bin/cargo"))
                    .firstOrNull { it.canExecute() }?.absolutePath ?: "cargo"
                val ndk = (System.getenv("ANDROID_NDK_ROOT") ?: System.getenv("ANDROID_NDK_HOME"))
                    ?.let(::File)?.takeIf { it.isDirectory }
                    ?: runCatching { ndkFromSdk.get().asFile }.getOrNull()?.takeIf { it.isDirectory }
                if (ndk == null) return@doLast skip("Android NDK not found")
                val bin = File(ndk, "toolchains/llvm/prebuilt").listFiles()?.firstOrNull { it.isDirectory }
                    ?.resolve("bin")
                val linker = bin?.resolve("$clangTriple$rustMinSdk-clang")
                if (linker == null || !linker.canExecute()) return@doLast skip("NDK linker not found")

                fun run(vararg cmd: String, env: Map<String, String> = emptyMap()): Int {
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
                val code = run(
                    cargo, "build", "--release", "--locked".takeIf { rustCrateDir.resolve("Cargo.lock").exists() } ?: "--quiet",
                    "--target", rustTriple,
                    env = mapOf(
                        "CARGO_TARGET_DIR" to rustTargetDir.get().asFile.absolutePath,
                        "CARGO_TARGET_${envKey}_LINKER" to linker.absolutePath,
                        // 16 KB page-size aligned segments (Android 15+).
                        "RUSTFLAGS" to "-C link-arg=-Wl,-z,max-page-size=16384",
                    ),
                )
                val built = rustTargetDir.get().asFile.resolve("$rustTriple/release/libssivcodecs.so")
                if (code != 0 || !built.isFile) return@doLast skip("cargo build failed")
                val dest = rustOutDir.get().asFile.resolve(abi).also { it.mkdirs() }
                built.copyTo(dest.resolve("libssivcodecs.so"), overwrite = true)
            } catch (e: Exception) {
                skip(e.toString())
            }
        }
    }
}

android.sourceSets.getByName("main").jniLibs.srcDir(rustOutDir)
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(rustTasks)
}
