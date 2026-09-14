package com.linroid.kiff.core

sealed class Edit {
  data class Insert(val position: Int, val lines: List<String>) : Edit()
  data class Delete(val position: Int, val count: Int) : Edit()
  data class Equal(val position: Int, val count: Int) : Edit()
}
