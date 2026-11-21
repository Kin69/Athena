
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
}

val athenaNdkVersion = providers.gradleProperty("athena.ndkVersion").getOrElse("30.0.16248370")

android {
    namespace = "com.kin.athena"
    compileSdk = 37
    compileSdkMinor = 1

    buildToolsVersion = "37.0.0"
    ndkVersion = athenaNdkVersion

    defaultConfig {
        applicationId = "com.kin.athena"
        minSdk = 24
        targetSdk = 37
        versionCode = 306
        versionName = "1.80"

        vectorDrawables {
            useSupportLibrary = true
        }
        androidResources {
            localeFilters += listOf("en","ca","de","es","fr","pl")
        }
        externalNativeBuild {
            cmake {
                cppFlags += ""
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    flavorDimensions += "store"
    productFlavors {
        create("playstore") {
            dimension = "store"
            buildConfigField("boolean", "USE_PLAY_BILLING", "true")
            buildConfigField("boolean", "CHECK_PREMIUM_CODE", "true")
            buildConfigField("String", "KOFI_URL", "\"https://ko-fi.com/s/b127ca6671\"")
        }
        create("fdroid") {
            dimension = "store"
            buildConfigField("boolean", "USE_PLAY_BILLING", "false")
            buildConfigField("boolean", "CHECK_PREMIUM_CODE", "true")
            buildConfigField("String", "KOFI_URL", "\"https://ko-fi.com/s/b127ca6671\"")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            /* Its own package id, so a debug build installs ALONGSIDE whatever
               release/store build is already on the device rather than trying to
               replace it. Without this, installing fails with
               INSTALL_FAILED_UPDATE_INCOMPATIBLE (different signing key), and
               the only way through is to uninstall -- which takes the user's
               firewall rules and settings with it. */
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
        }
    }
    
    tasks.whenTaskAdded {
        if (name.startsWith("merge") && name.contains("JniLibFolders")) {
            dependsOn("buildGoLibraries")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    testOptions {
        // AGP 9's Compose Preview task refuses to configure without this.
        unitTests.isIncludeAndroidResources = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE.md",
                "/META-INF/README.md",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST"
            )
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.directories.add("libs/")
        }
    }
    
    tasks.register<Exec>("buildGoLibraries") {
        group = "build"
        description = "Build Go libraries for Android"
        
        val goDir = layout.projectDirectory.dir("src/main/go")
        val libsDir = layout.projectDirectory.dir("libs")
        
        inputs.dir(goDir)
        outputs.files(
            libsDir.file("arm64-v8a/libnflog.so"),
            libsDir.file("armeabi-v7a/libnflog.so")
        )
        outputs.upToDateWhen { false }
        
        workingDir(goDir)
        environment("NDK_VERSION", athenaNdkVersion)
        
        val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
        val buildScript = if (isWindows) "build-android.bat" else "build-android.sh"
        
        if (isWindows) {
            commandLine("cmd", "/c", buildScript)
        } else {
            commandLine("bash", buildScript)
        }
        
        doFirst {
            val buildScriptFile = goDir.file(buildScript).asFile
            if (!buildScriptFile.exists()) {
                throw GradleException("Build script $buildScript not found in ${goDir.asFile}")
            }
            
            libsDir.dir("arm64-v8a").asFile.mkdirs()
            libsDir.dir("armeabi-v7a").asFile.mkdirs()
        }
    }
    ksp {
        arg("dagger.fastInit", "enabled")
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            /* No version pin. It used to request 3.22.1, which resolves to the
               SDK's own bundled copy -- and that copy is x86-64, so it died with
               exit 126 here. local.properties now points cmake.dir at the
               system CMake, which is ARM-native; naming a version as well would
               send AGP back to the SDK looking for an exact match it cannot
               run. CMakeLists.txt asks for >= 3.22.1 and the system CMake is
               4.4.3, so the requirement is still satisfied. */
        }
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(libs.m3color)

    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.common)
    ksp(libs.hilt.android.compiler)
    ksp(libs.hilt.compile)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)

    // Room
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.splashscreen)

    // AndroidX
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore.preferences)

    // Retrofit
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.converter.scalars)

    // Notification
    implementation(libs.androidx.core)

    //Fingerprint
    implementation(libs.androidx.biometric)

    // DNS Blocking
    implementation(libs.atomicfu)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.dnsjava)
    implementation(libs.pcap4j.core)

    //Billing (Play Store only)
    "playstoreImplementation"(libs.billing)

    // Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
