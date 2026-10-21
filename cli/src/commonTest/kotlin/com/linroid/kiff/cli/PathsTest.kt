package com.linroid.kiff.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path.Companion.toPath

class PathsTest {

  @Test
  fun aWindowsPathIsAbsoluteFromADriveWithEitherSlashOrFromAShare() {
    for (path in listOf("C:\\x", "C:/x", "c:\\", "D:/a/_temp/home", "\\\\server\\share\\x",
      "//server/share/x")) {
      assertTrue(isAbsolutePath(path, windows = true), path)
    }
    // Relative to the working directory, or to the current drive's.
    for (path in listOf("x", "x\\y", "x/y", "C:x", "\\x", "/x", "", ":/x", "1:/x")) {
      assertFalse(isAbsolutePath(path, windows = true), path)
    }
  }

  @Test
  fun aPosixPathIsAbsoluteFromTheRootAlone() {
    assertTrue(isAbsolutePath("/x", windows = false))
    assertTrue(isAbsolutePath("//x", windows = false))
    for (path in listOf("x", "C:/x", "C:\\x", "\\\\server\\share")) {
      assertFalse(isAbsolutePath(path, windows = false), path)
    }
  }

  @Test
  fun windowsSlashesBecomeOkiosSoItSeesTheRoot() {
    // okio reads C:/ as relative and //server as one root; with backslashes, as Windows does.
    val drive = platformPath("D:/a/_temp/home", windows = true)
    assertEquals("D:\\a\\_temp\\home", drive.toString())
    assertTrue(drive.isAbsolute)
    val share = platformPath("//server/share/x", windows = true)
    assertEquals("\\\\server\\share\\x", share.toString())
    assertTrue(share.isAbsolute)
    assertEquals("a/b", platformPath("a/b", windows = false).toString())
  }

  @Test
  fun anAbsolutePathIsKeptAsItIsAndARelativeOneJoinsTheWorkingDirectory() {
    val here = systemFileSystem.canonicalize(".".toPath())
    assertEquals(here / "x" / "z", absolute("x/./y/../z"))
    if (isWindows) {
      assertEquals("D:\\a\\_temp\\home", absolute("D:/a/_temp/./x/../home").toString())
      assertEquals("C:\\Users\\me\\.kiff", absolute("C:\\Users\\me\\.kiff").toString())
      assertEquals("\\\\server\\share\\y", absolute("\\\\server\\share\\x\\..\\y").toString())
      assertEquals("\\\\server\\share\\y", absolute("//server/share/y").toString())
    } else {
      assertEquals("/a/home", absolute("/a/./x/../home").toString())
    }
  }

  @Test
  fun aFileIsNamedByItsLastSegmentWhicheverSlashCameBeforeIt() {
    assertEquals("app.apk", Labeled.parse("builds/v=2/app.apk").fileName)
    if (isWindows) {
      assertEquals("app.apk", Labeled.parse("C:\\builds/v2\\app.apk").fileName)
      assertEquals("C:\\out\\x.kiff", join("C:/out", "x.kiff"))
    }
  }
}
