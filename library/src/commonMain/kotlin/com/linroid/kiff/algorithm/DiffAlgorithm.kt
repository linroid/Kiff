package com.linroid.kiff.algorithm

import com.linroid.kiff.core.Edit

interface DiffAlgorithm {
  val name: String
  fun diff(source: List<String>, target: List<String>): List<Edit>
}
