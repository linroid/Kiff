plugins {
  alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
  jvm()

  iosArm64()
  iosSimulatorArm64()

  macosArm64()
  macosX64()

  linuxX64()
  mingwX64()

  js {
    nodejs()
    browser()
  }

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain.dependencies {
      implementation(libs.okio)
    }
    // okio's file system lives in a separate artifact on JS; everywhere else it is in okio core.
    jsMain.dependencies {
      implementation(libs.okio.nodefilesystem)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}
