import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 版本名 yy.MM.dd（GMT+8），CI 可用 -PversionName 覆盖
fun computeVersionName(): String {
    if (project.hasProperty("versionName")) return project.property("versionName") as String
    val fmt = SimpleDateFormat("yy.MM.dd")
    fmt.timeZone = TimeZone.getTimeZone("GMT+8")
    return fmt.format(Date())
}

// versionCode 同源日期制（yyMMddHH），保证覆盖安装可识别升级
fun computeVersionCode(): Int {
    val fmt = SimpleDateFormat("yyMMddHH")
    fmt.timeZone = TimeZone.getTimeZone("GMT+8")
    return fmt.format(Date()).toInt()
}

val goAarDir = layout.projectDirectory.dir("libs")
val hasGoAar = goAarDir.asFile.listFiles { f: File -> f.extension == "aar" }?.isNotEmpty() == true

// 签名：读取根 local.properties（CI 注入 KEY_PATH/ALIAS_NAME/ALIAS_PASSWORD/KEY_PASSWORD，
// 与源项目同名键），缺失时回退 debug 签名，本地构建不受影响
val keystoreProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseKeyPath: String? = keystoreProperties.getProperty("KEY_PATH")
val hasReleaseKeystore = !releaseKeyPath.isNullOrBlank() &&
    rootProject.file(releaseKeyPath).exists() &&
    !keystoreProperties.getProperty("ALIAS_NAME").isNullOrBlank()

// CI release：按 ABI 分包（对齐源项目 flutter build apk --split-per-abi）
val releaseRequested = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true) || it.contains("bundle", ignoreCase = true)
}

android {
    namespace = "com.xaxka.openlist"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xaxka.openlist"
        minSdk = 21
        targetSdk = 36
        versionCode = computeVersionCode()
        versionName = computeVersionName()
    }

    // 无 Go AAR（本地/无 gomobine 环境）时启用 alistlib 编译桩，保证全量编译可跑
    if (!hasGoAar) {
        sourceSets.getByName("main") {
            java.srcDir("src/stub/kotlin")
        }
    }

    // ABI 分包（仅 release 任务启用，本地 debug 不受影响）
    splits {
        abi {
            isEnable = releaseRequested
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(releaseKeyPath!!)
                storePassword = keystoreProperties.getProperty("KEY_PASSWORD")
                keyAlias = keystoreProperties.getProperty("ALIAS_NAME")
                keyPassword = keystoreProperties.getProperty("ALIAS_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // .so 压缩存储：Go 内核 .so 体积大，extractNativeLibs=false 时未压缩会显著撑大下载体积
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/**/LICENSE*",
            "/META-INF/**/NOTICE*",
        )
    }

    lint {
        abortOnError = false
        disable += "MissingTranslation"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    if (hasGoAar) {
        implementation(fileTree(goAarDir) { include("*.aar") })
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
