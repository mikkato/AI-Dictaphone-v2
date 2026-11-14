import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
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
    // NDK 28.2 уже установлен в SDK; без пина AGP тянет свой 27.0 (~1 ГБ)
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "ru.mytolm.aidictaphone"
        minSdk = 35
        targetSdk = 36
        versionCode = 10
        versionName = "3.0.2"
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
            version = "3.22.1"
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
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.media3.exoplayer)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(composeBom)
}

// Room: экспортируем схему при сборке — с v3 начинается история миграций
// (без неё следующий bump версии физически нечем описать)
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
