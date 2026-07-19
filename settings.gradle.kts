pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    maven { url = uri("https://api.xposed.info/") }
    maven { url = uri("https://jitpack.io") } // EzXHelper (hook 功能依赖)
  }
}

rootProject.name = "蓝钩"

include(":app")

// proot 环境下 app/build 混入了其他 uid (rv2ide) 的目录, root 无法删除/写入,
// 将构建输出重定向到完全由 root 控制的独立目录, 避免权限冲突。
gradle.beforeProject {
    if (name == "app") {
        layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("../../llhook-build/app"))
    }
}
