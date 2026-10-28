// ApiKeys - access to cloud API keys
//
// Keys are injected by Gradle from local.properties into debug builds only (see app/build.gradle.kts);
// release builds always see null here. Never log, display or send a key anywhere except the
// Authorization / key parameter of its own API.

package com.qolaijol.app.config

import com.qolaijol.app.BuildConfig

object ApiKeys {

  /** Groq (speech-to-text, scene description), or null if not configured. */
  val groq: String?
    get() = BuildConfig.GROQ_API_KEY.takeIf { it.isNotBlank() }

  /** 2GIS (place search, routing), or null if not configured. */
  val dgis: String?
    get() = BuildConfig.DGIS_API_KEY.takeIf { it.isNotBlank() }

  /** Safe to log: which keys are present, never their values. */
  fun describe(): String =
      "groq=${if (groq != null) "configured" else "missing"}, " +
          "2gis=${if (dgis != null) "configured" else "missing"}"
}
