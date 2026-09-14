package com.linroid.kiff.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.linroid.kiff.apk.ApkDiffReport
import com.linroid.kiff.Kiff
import com.linroid.kiff.zip.ZipEntryChange
import com.linroid.kiff.zip.ZipEntryStatus
import com.linroid.kiff.io.KiffFiles

class ChangesCommand : KiffCommand(name = "changes") {
  override fun help(context: Context) = "List what changed entry by entry between two archives"

  private val all by option("--all", help = "Include unchanged entries").flag()
  private val source by argument(help = "Original archive")
  private val target by argument(help = "Updated archive")

  override fun execute() {
    requireFile(source)
    requireFile(target)
    val sourceBytes = KiffFiles.readBytes(source)
    val targetBytes = KiffFiles.readBytes(target)
    val apk = if (Kiff.apk.isApk(sourceBytes) || Kiff.apk.isApk(targetBytes)) {
      Kiff.apk.analyze(sourceBytes, targetBytes)
    } else {
      null
    }
    val report = apk?.entries ?: Kiff.zip.analyze(sourceBytes, targetBytes)

    echo("${formatBytes(report.sourceSize.toLong())} -> ${formatBytes(report.targetSize.toLong())}")
    echo(
      ZipEntryStatus.entries.joinToString("  ") { status ->
        "${status.name.lowercase()}=${report.count(status)}"
      }
    )
    if (apk != null) {
      echo("")
      echoKinds(apk)
    }
    echo("")
    val shown = if (all) report.changes else report.changed
    for (change in shown.sortedByDescending { maxOf(it.sourceSize, it.targetSize) }) {
      echo("  ${marker(change.status)} ${change.name}${sizes(change)}")
    }
    if (shown.isEmpty()) echo("  (no differences)")
  }

  private fun echoKinds(report: ApkDiffReport) {
    for (kind in report.kinds) {
      val counts = buildList {
        add("${kind.entryCount} ${if (kind.entryCount == 1) "entry" else "entries"}")
        if (kind.changedCount > 0) add("${kind.changedCount} changed")
        if (kind.addedCount > 0) add("${kind.addedCount} added")
        if (kind.removedCount > 0) add("${kind.removedCount} removed")
      }
      val growth = if (kind.growth == 0L) "" else " ${signed(kind.growth)}"
      echo(
        "  ${kind.kind.name.lowercase().padEnd(15)} ${counts.joinToString(", ")}, " +
          "${formatBytes(kind.targetBytes)}$growth"
      )
    }
    if (report.signed) {
      val from = formatBytes(report.sourceSigningBlockSize.toLong())
      val to = formatBytes(report.targetSigningBlockSize.toLong())
      echo("  ${"signing block".padEnd(15)} $from -> $to")
    }
  }

  private fun signed(growth: Long) =
    if (growth > 0) "(+${formatBytes(growth)})" else "(-${formatBytes(-growth)})"

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
