package com.linroid.kiff.example

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.linroid.kiff.Kiff
import com.linroid.kiff.ZipEntryChange
import com.linroid.kiff.ZipEntryStatus
import com.linroid.kiff.io.KiffFiles

class ChangesCommand : CliktCommand(name = "changes") {
  override fun help(context: Context) = "List what changed entry by entry between two archives"

  private val all by option("--all", help = "Include unchanged entries").flag()
  private val source by argument(help = "Original archive")
  private val target by argument(help = "Updated archive")

  override fun run() {
    requireFile(source)
    requireFile(target)
    val report = Kiff.zip.analyze(KiffFiles.readBytes(source), KiffFiles.readBytes(target))

    echo("${formatBytes(report.sourceSize.toLong())} -> ${formatBytes(report.targetSize.toLong())}")
    echo(
      ZipEntryStatus.entries.joinToString("  ") { status ->
        "${status.name.lowercase()}=${report.count(status)}"
      }
    )
    echo("")
    val shown = if (all) report.changes else report.changed
    for (change in shown.sortedByDescending { maxOf(it.sourceSize, it.targetSize) }) {
      echo("  ${marker(change.status)} ${change.name}${sizes(change)}")
    }
    if (shown.isEmpty()) echo("  (no differences)")
  }

  private fun marker(status: ZipEntryStatus) = when (status) {
    ZipEntryStatus.UNCHANGED -> "="
    ZipEntryStatus.METADATA_CHANGED -> "~"
    ZipEntryStatus.MODIFIED -> "M"
    ZipEntryStatus.ADDED -> "+"
    ZipEntryStatus.REMOVED -> "-"
  }

  private fun sizes(change: ZipEntryChange) = when (change.status) {
    ZipEntryStatus.ADDED -> " (${formatBytes(change.targetSize.toLong())})"
    ZipEntryStatus.REMOVED -> " (${formatBytes(change.sourceSize.toLong())})"
    ZipEntryStatus.MODIFIED ->
      " (${formatBytes(change.sourceSize.toLong())} -> ${formatBytes(change.targetSize.toLong())})"
    else -> ""
  }
}
