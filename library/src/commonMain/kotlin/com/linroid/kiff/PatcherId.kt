package com.linroid.kiff

/**
 * The header's `patcher` byte: what the writer took the target to be as a whole.
 *
 * Kiff picks it from the target, and reads it back as [PatchInfo.patcher] for display. Nothing in
 * a patch's payload depends on it, so it never decides how a patch is applied. Version 1 of the
 * format has these three codes, and a reader refuses any other.
 */
public enum class PatcherId(public val code: Int) {
  BINARY(1),
  ZIP(2),
  APK(3);

  internal companion object {
    fun fromCode(code: Int): PatcherId = entries.firstOrNull { it.code == code }
      ?: throw KiffException.InvalidPatch("Unknown patcher code $code")
  }
}
