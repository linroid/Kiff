package com.linroid.kiff.text

interface LineDiffAlgorithm {
  val name: String
  fun diff(source: List<String>, target: List<String>): List<Edit>
}
