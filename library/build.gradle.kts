plugins {
  alias(libs.plugins.kotlin.multiplatform)
}

group = "com.linroid.kiff"
version = "0.1.0"

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
      implementation(libs.kotlinx.io.core)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}
