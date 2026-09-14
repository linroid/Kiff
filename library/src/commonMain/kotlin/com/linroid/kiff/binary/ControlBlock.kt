package com.linroid.kiff.binary

data class ControlBlock(
  val diffLength: Int,
  val extraLength: Int,
  val oldSeek: Int,
)
