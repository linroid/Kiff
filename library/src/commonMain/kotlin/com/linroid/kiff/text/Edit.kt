package com.linroid.kiff.text

public sealed class Edit {
  public data class Insert(val position: Int, val lines: List<String>) : Edit()
  public data class Delete(val position: Int, val count: Int) : Edit()
  public data class Equal(val position: Int, val count: Int) : Edit()
}
