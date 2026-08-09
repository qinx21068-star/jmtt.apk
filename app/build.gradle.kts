import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 开启 Compose 编译器稳定性报告，诊断 ComicCard 是否真的被 skip
// v27.14：临时关闭 reportsDestination 以降低 R8 阶段内存峰值（4GB 容器 OOM），
// 报告对运行时无影响，需要诊断时再打开。
composeCompiler {
    // reportsDestination = layout.projectDirectory.dir("compose-reports")
    // v27.5 性能修复（用户连续多轮反馈"所有界面上下滑动都卡"）：
    // 1. 启用 OptimizeNonSkippingGroups：将非 skippable 的 Composable group 优化为不生成独立 group，
    //    减少 currentComposer.startGroup/endGroup 开销。4 个屏幕都有大量非 skippable Composable，
    //    group 维护开销在每次 fling 帧都成倍放大。
    // 2. stabilityConfigurationFiles：声明 AppContainer 等依赖容器为 Stable，避免被 Compose 推断为
    //    Unstable 导致屏幕根 Composable 无法 skip。
    featureFlags.addAll(
        org.jetbrains.kotlin.compose.compiler.gradle.ComposeFeatureFlag.OptimizeNonSkippingGroups,
    )
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability-config.conf"))
}

android {
    namespace = "com.jmreader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jmreader"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // v27.5 稳定性加固：签名配置从 local.properties 或环境变量读取，避免密码明文提交到版本库。
    // 读取优先级：环境变量 > local.properties > fallback 兜底（保证 CI/本地无配置也能构建）。
    // local.properties 已在 .gitignore 中，不会误提交。
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) runCatching { load(f.inputStream()) }
    }
    val storePw = System.getenv("JM_KEYSTORE_PW") ?: localProps.getProperty("jm.keystore_pw") ?: "jmreader2024"
    val keyPw = System.getenv("JM_KEY_PW") ?: localProps.getProperty("jm.key_pw") ?: "jmreader2024"
    val keyAlias = System.getenv("JM_KEY_ALIAS") ?: localProps.getProperty("jm.key_alias") ?: "jmreader"

    signingConfigs {
        create("release") {
            storeFile = file("../release.keystore")
            storePassword = storePw
            this.keyAlias = keyAlias
            keyPassword = keyPw
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 正式版签名：使用项目根目录的 release.keystore
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.telephoto.zoomable.image.coil)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jsoup)

    // v27.5 #28：应用锁（指纹/PIN）
    implementation(libs.androidx.biometric)
    // v27.5 #28：ProcessLifecycleOwner 用于检测 App 前后台切换
    implementation(libs.androidx.lifecycle.process)
    // v27.6：DocumentFile 用于 SAF 外部存储下载支持
    implementation("androidx.documentfile:documentfile:1.0.1")

    debugImplementation(libs.androidx.ui.tooling)
}
