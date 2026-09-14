package com.linroid.kiff.text

import com.linroid.kiff.text.Edit

interface LineDiffAlgorithm {
  val name: String
  fun diff(source: List<String>, target: List<String>): List<Edit>
}
