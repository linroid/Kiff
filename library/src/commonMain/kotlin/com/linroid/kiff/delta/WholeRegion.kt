package com.linroid.kiff.delta

import com.linroid.kiff.format.ByteWriter
import com.linroid.kiff.format.RegionNode
import com.linroid.kiff.format.beatsStoring
import com.linroid.kiff.format.structureBytes
import com.linroid.kiff.io.ByteArraySource
import com.linroid.kiff.io.toIntIndex

/**
 * Describes a whole input as one leaf: the case where nothing is known about its structure.
 *
 * Even here the result has to beat storing the bytes. Two files with nothing in common produce a
 * delta larger than the target, and a patch that big is worse than no patch at all.
 */
internal fun encodeWhole(
  source: ByteArraySource,
  target: ByteArraySource,
  algorithm: DeltaAlgorithm,
  literals: ByteWriter
): RegionNode {
  val span = target.size.toIntIndex("Target size")
  val scratch = ByteWriter(span.coerceIn(64, 1 shl 16))
  val writer = DeltaWriter(source.bytes, scratch)
  algorithm.scanner(source).use { it.scan(target, 0, target.size, writer) }
  val node = RegionNode.Delta(target.size, writer.finishInstructions())
  val candidate = scratch.toByteArray()
  if (!beatsStoring(node.structureBytes(), candidate, target.bytes, 0, span)) {
    literals.writeBytes(target.bytes, 0, span)
    return RegionNode.Raw(target.size)
  }
  literals.writeBytes(candidate)
  return node
}
