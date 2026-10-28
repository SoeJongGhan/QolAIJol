/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

val localProperties =
    Properties().apply {
      val file = rootDir.resolve("local.properties")
      if (file.exists()) {
        file.inputStream().use { load(it) }
      }
    }

// A local.properties value as a Java string literal for buildConfigField.
fun localString(key: String): String =
    "\"" + localProperties.getProperty(key, "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
  namespace = "com.qolaijol.app"
  compileSdk = 36

  buildFeatures { buildConfig = true }

  defaultConfig {
    applicationId = "com.qolaijol.app"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // ONNX Runtime ships native libraries for every ABI (~240 MB debug APK). Keep 64-bit ARM for
    // phones and x86_64 for the emulator; minSdk 31 devices are 64-bit.
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

    // Meta Wearables Device Access Toolkit Setup
    // Without Developer Mode, these values need to be set with credentials from the app registered
    // in Wearables Developer Center
    manifestPlaceholders["mwdat_application_id"] =
        localProperties.getProperty("mwdat_application_id", "")
    manifestPlaceholders["mwdat_client_token"] =
        localProperties.getProperty("mwdat_client_token", "")
  }

  buildTypes {
    // Cloud API keys (Groq, 2GIS) come from local.properties and exist ONLY in debug builds, for
    // testing on the developer's own phone. Never share a debug APK. Release builds get empty keys;
    // production will call a server (Supabase Edge Function) that holds the keys instead.
    debug {
      buildConfigField("String", "GROQ_API_KEY", localString("groq_api_key"))
      buildConfigField("String", "DGIS_API_KEY", localString("dgis_api_key"))
    }
    release {
      buildConfigField("String", "GROQ_API_KEY", "\"\"")
      buildConfigField("String", "DGIS_API_KEY", "\"\"")
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.material3)
  implementation(libs.kotlinx.collections.immutable)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.inputs)
  implementation(libs.mwdat.mockdevice)
  implementation(libs.onnxruntime.android)
  implementation(libs.uvc.android)
  testImplementation(libs.junit)
  testImplementation(libs.org.json)
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.rules)
}
