package com.linroid.kiff

/** Identifies the algorithm that produced a patch, so patches can be applied without guessing. */
enum class AlgorithmId(val code: Int) {
  BINARY(1),
  ZIP(2),
  APK(3);

  companion object {
    fun fromCode(code: Int): AlgorithmId = entries.firstOrNull { it.code == code }
      ?: throw KiffException.InvalidPatch("Unknown algorithm code $code")
  }
}
