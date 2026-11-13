import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Прод-подпись: keystore.properties + release.jks в корне проекта.
// Оба файла приватные (chmod 600) — вне репозитория не показывать.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "ru.mytolm.aidictaphone"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.mytolm.aidictaphone"
        minSdk = 35
        targetSdk = 36
        versionCode = 7
        versionName = "2.1.2"
        ndk {
            // Только arm64: все целевые телефоны и эмулятор на Apple Silicon — arm64.
            // x86_64 удваивал вес нативных библиотек (whisper+llama+onnx).
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DWHISER_BUILD_TYPES=STATIC",
                    "-DWHISER_USE_GGML=ON",
                    "-DWHISER_ENABLE_COREML=OFF",
                    "-DWHISER_ENABLE_VITS_AI=OFF",
                    "-DWHISER_ENABLE_OPENVINO=OFF",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CCACHE=OFF",
                    "-DGGML_ALL_WARNINGS=OFF",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
            }
        }
        buildConfigField("long", "MODEL_CHUNK_WINDOW_MS", "600000")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: вырезает неиспользуемый код Compose/Media3/OkHttp после минификации.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (keystoreProps.isNotEmpty())
                signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            // ggml динамические CPU-бэкенды грузятся dlopen() по пути —
            // нужен реальный filesytem-path (extractNativeLibs), а не mmap из APK
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.media3:media3-exoplayer:1.4.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation(composeBom)
}
