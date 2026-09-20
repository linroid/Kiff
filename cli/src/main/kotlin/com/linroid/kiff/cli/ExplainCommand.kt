package com.linroid.kiff.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.linroid.kiff.Kiff
import com.linroid.kiff.apk.ApkPatchReport
import com.linroid.kiff.io.KiffFiles
import com.linroid.kiff.region.RegionCost
import com.linroid.kiff.region.RegionReport

/**
 * Answers "where did the patch bytes go", which neither `create` nor `changes` can: `create` gives
 * one total and `changes` says what differs, but a large entry can change and still be nearly free
 * while a small one can be expensive.
 */
class ExplainCommand : KiffCommand(name = "explain") {
  override fun help(context: Context) = "Show where a patch's bytes go, region by region"

  private val all by option("--all", help = "List every region, not just the costly ones").flag()
  private val tree by option(
    "--tree",
    help = "Show the regions inside a region: a dex's sections, an inner archive's entries"
  ).flag()
  private val source by argument(help = "Original file")
  private val target by argument(help = "Updated file")

  override fun execute() {
    requireFile(source)
    requireFile(target)
    val sourceBytes = KiffFiles.readBytes(source)
    val targetBytes = KiffFiles.readBytes(target)

    if (Kiff.apk.isApk(sourceBytes) || Kiff.apk.isApk(targetBytes)) {
      val report = Kiff.apk.explainKinds(sourceBytes, targetBytes)
      echoTotals(report.regions)
      echoKinds(report)
      echoRegions(report.regions)
    } else {
      val report = Kiff.zip.explain(sourceBytes, targetBytes)
      echoTotals(report)
      echoRegions(report)
    }
  }

  private fun echoTotals(report: RegionReport) {
    echo("${formatBytes(report.targetSize)} target, ${report.regions.size} regions")
    val share = formatPercent(report.patchSize.toDouble() / report.targetSize)
    echo(total("Patch", "${formatBytes(report.patchSize)} ($share of target)"))
    echo(total("Content", "${formatBytes(report.literalBytes)} carried, before it is packed"))
    echo(total("Instructions", "${formatBytes(report.instructionBytes)} of references"))
    echo(total("Referenced", "${report.referenced.size} regions the patch only points at"))
  }

  private fun total(label: String, value: String) = "  ${"$label:".padEnd(14)}$value"

  private fun echoKinds(report: ApkPatchReport) {
    val total = report.regions.attributedBytes.coerceAtLeast(1)
    echo("")
    echo("By kind")
    for (kind in report.kinds) {
      val entries = "${kind.entryCount} ${if (kind.entryCount == 1) "entry" else "entries"}"
      echo(
        row(kind.kind.name.lowercase(), kind.patchBytes, total) +
          "   $entries, ${formatPercent(kind.ratio)} of their ${formatBytes(kind.targetBytes)}"
      )
    }
    if (report.gapBytes > 0) echo(row("gaps", report.gapBytes, total))
    if (report.directoryBytes > 0) echo(row("directory", report.directoryBytes, total))
  }

  private fun row(label: String, bytes: Long, total: Long) =
    "  ${label.padEnd(16)}${formatBytes(bytes).padStart(10)}" +
      formatPercent(bytes.toDouble() / total).padStart(9)

  private fun echoRegions(report: RegionReport) {
    if (tree) {
      echo("")
      echo("Regions, and what they were described as")
      for (region in report.regions.sortedByDescending { it.patchBytes }) {
        if (region.patchBytes == 0L && !all) continue
        echoTree(region, depth = 0)
      }
      return
    }
    val costly = report.costly
    val shown = if (all) costly else costly.take(TOP_REGIONS)
    echo("")
    echo(if (all) "Every region that cost anything" else "Most expensive regions")
    for (region in shown) {
      echo("  ${formatBytes(region.patchBytes).padStart(10)}  ${describe(region)}")
    }
    if (costly.isEmpty()) echo("  (the target was described entirely by copies)")
    val hidden = costly.size - shown.size
    if (hidden > 0) echo("  ... and $hidden more, use --all")
    val deeper = report.allRegions.size - report.regions.size
    if (deeper > 0) echo("  ($deeper regions nested inside these, use --tree)")
  }

  private fun echoTree(region: RegionCost, depth: Int) {
    val indent = "  ".repeat(depth + 1)
    echo("${formatBytes(region.patchBytes).padStart(10)}  $indent${describe(region)}")
    for (child in region.children.sortedByDescending { it.patchBytes }) {
      if (child.patchBytes == 0L && !all) continue
      echoTree(child, depth + 1)
    }
  }

  private fun describe(region: RegionCost) =
    "${region.name} (${formatPercent(region.ratio)} of ${formatBytes(region.targetBytes)})"

  private companion object {
    const val TOP_REGIONS = 15
  }
}
