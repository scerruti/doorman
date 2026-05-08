package com.otabi.doorman

enum class MacExtractionMode { BYTES, STRICT_WITH_GAP, LAST_RUN }

object MacExtractionConfig {
    val mode: MacExtractionMode = System.getenv("MAC_EXTRACTION_MODE")?.uppercase()?.let {
        try { MacExtractionMode.valueOf(it) } catch (_: Exception) { MacExtractionMode.BYTES }
    } ?: MacExtractionMode.BYTES
}
