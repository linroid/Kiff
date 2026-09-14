package com.linroid.kiff

/** Identifies the patcher that produced a patch, so patches can be applied without guessing. */
enum class PatcherId(val code: Int) {
  BINARY(1),
  ZIP(2),
  APK(3);

  companion object {
    fun fromCode(code: Int): PatcherId = entries.firstOrNull { it.code == code }
      ?: throw KiffException.InvalidPatch("Unknown patcher code $code")
  }
}
