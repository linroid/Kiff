package com.linroid.kiff.binary

data class BinaryPatch(
  val controlBlocks: List<ControlBlock>,
  val diffBytes: ByteArray,
  val extraBytes: ByteArray,
  val newSize: Int,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is BinaryPatch) return false
    return controlBlocks == other.controlBlocks &&
      diffBytes.contentEquals(other.diffBytes) &&
      extraBytes.contentEquals(other.extraBytes) &&
      newSize == other.newSize
  }

  override fun hashCode(): Int {
    var result = controlBlocks.hashCode()
    result = 31 * result + diffBytes.contentHashCode()
    result = 31 * result + extraBytes.contentHashCode()
    result = 31 * result + newSize
    return result
  }
}
