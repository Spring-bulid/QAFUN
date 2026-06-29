import com.android.build.api.dsl.ApplicationExtension
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val keystorePropertiesFile = rootProject.file("app/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

extensions.configure<ApplicationExtension> {
    namespace = "me.yxp.qfun"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.yxp.qfun"
        minSdk = 26
        targetSdk = 37
        versionCode = 35
        versionName = "2.10"

        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties.getProperty("keyAlias") ?: ""
            keyPassword = keystoreProperties.getProperty("keyPassword") ?: ""
            storePassword = keystoreProperties.getProperty("storePassword") ?: ""
            val storeFileName = keystoreProperties.getProperty("storeFile") ?: ""
            if (storeFileName.isNotEmpty()) {
                storeFile = file(storeFileName)
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        release {
            // 注：R8 混淆需较大内存（2.5G+），在内存受限的 CI/cgroup 环境
            // 可能 OOM；此时临时改为 false 可跳过 R8 打包验证功能。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    androidResources {
        additionalParameters += listOf(
            "--allow-reserved-package-id",
            "--package-id",
            "0x44",
        )
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF*.proto"
            )
            pickFirsts += setOf(
                "META-INF/xposed/**",
                "META-INF/services/**"
            )
        }
    }

}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.dalvik.dx)
    implementation(libs.libxposed.service)
    implementation(projects.annotation)
    implementation(libs.dexkit)
    implementation(libs.protobuf.java)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.activity)
    implementation(libs.compose.animation)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kyant0.backdrop )
    implementation(libs.quickjs.kt.android)

    ksp(projects.processor)

    compileOnly(libs.libxposed.api)
    compileOnly(libs.xposed)
    compileOnly(projects.qqinterface)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.35.0"
    }

    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

val adb: String = androidComponents.sdkComponents.adb.get().asFile.absolutePath
val packageName = "com.tencent.mobileqq"
// adb shell am force-stop com.tencent.mobileqq
val killQQ = tasks.register<Exec>("killQQ") {
    description = ""
    group = "qfun"
    commandLine(adb, "shell", "am", "force-stop", packageName)
    isIgnoreExitValue = true
}
