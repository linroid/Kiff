package com.linroid.kiff

import com.linroid.kiff.container.ContainerRegistry
import com.linroid.kiff.delta.DeltaAlgorithm
import com.linroid.kiff.delta.RollingHashAlgorithm
import com.linroid.kiff.region.RegionPlanner
import com.linroid.kiff.region.TextAwareRegionPlanner
import com.linroid.kiff.text.LineDiffAlgorithm
import com.linroid.kiff.text.MyersDiffAlgorithm

/**
 * How [Kiff.createPatch] builds a patch.
 *
 * None of it is recorded in the patch. Each setting changes how the target is described, never how
 * a description is read, so a patch built with any of them applies with no options at all.
 */
class KiffOptions(
  /** The byte search inside every region. Any algorithm works with any structure. */
  val algorithm: DeltaAlgorithm = RollingHashAlgorithm,
  /** Chooses how each leaf region of a container is described; see [RegionPlanner]. */
  val planner: RegionPlanner = TextAwareRegionPlanner,
  /**
   * The formats Kiff takes apart: the file as a whole, and any region found inside it.
   *
   * Add a format here to have it recognised at either level. An empty registry describes every
   * file as plain bytes.
   */
  val containers: ContainerRegistry = NestedContainers,
  /** The line diff behind regions described as text. */
  val lineAlgorithm: LineDiffAlgorithm = MyersDiffAlgorithm()
)
