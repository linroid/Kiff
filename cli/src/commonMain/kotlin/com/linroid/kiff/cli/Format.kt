package com.linroid.kiff.cli

/** Human-readable byte count, e.g. `1.4 MiB`. */
fun formatBytes(bytes: Long): String {
  val units = listOf("B", "KiB", "MiB", "GiB")
  var value = bytes.toDouble()
  var unit = 0
  while (value >= 1024 && unit < units.lastIndex) {
    value /= 1024
    unit++
  }
  val rendered = if (unit == 0) value.toInt().toString() else formatDouble(value, 1)
  return "$rendered ${units[unit]}"
}

fun formatPercent(ratio: Double): String = "${formatDouble(ratio * 100, 2)}%"

private fun formatDouble(value: Double, decimals: Int): String {
  var factor = 1L
  repeat(decimals) { factor *= 10 }
  val scaled = (value * factor).toLong()
  val whole = scaled / factor
  val fraction = (scaled % factor).toString().padStart(decimals, '0')
  return if (decimals == 0) "$whole" else "$whole.$fraction"
}
