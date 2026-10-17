plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.inksyncboox"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.inksyncboox"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
      // libarchive and the Onyx SDK can ship the same native helper libraries
      jniLibs {
        pickFirsts += "**/*.so"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Lifecycle / ViewModel
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Lets the Boox e-ink refresh call reach Onyx's non-SDK View methods (Android hidden-API rules)
  implementation(libs.hiddenapibypass)

  // libarchive for CBR (RAR4/RAR5) and CB7 comics
  implementation(libs.libarchive)

  // Onyx Boox pen SDK (low-latency raw drawing). Exclusions match the Notable app's setup.
  implementation(libs.onyxsdk.device) {
    exclude(group = "com.android.support", module = "support-compat")
  }
  implementation(libs.onyxsdk.pen) {
    exclude(group = "com.android.support", module = "support-compat")
    exclude(group = "com.android.support", module = "appcompat-v7")
    exclude(group = "com.onyx.android.sdk", module = "onyxsdk-geometry")
    exclude(group = "com.tencent", module = "mmkv")
  }
  implementation(libs.onyxsdk.base) {
    exclude(group = "com.android.support", module = "support-compat")
    exclude(group = "com.android.support", module = "appcompat-v7")
    exclude(group = "com.tencent", module = "mmkv")
    // Old permissions helper only published to the retired JCenter; not used by the pen layer.
    exclude(group = "pub.devrel", module = "easypermissions")
  }
  implementation(libs.rxjava)
  implementation(libs.rxandroid)
}
