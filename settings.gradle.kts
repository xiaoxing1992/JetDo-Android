pluginManagement {
    repositories {
        maven { setUrl("https://maven.aliyun.com/repository/central") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 不用 mavenLocal()：F-Droid 构建机没有本地 Maven 仓库，
        // 留着只会让「同一坐标在本地是旧版、线上是新版」这类问题更难排查。
        maven { setUrl("https://maven.aliyun.com/repository/central") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "JetDo-Android"
include(":app")
include(":core:model")
include(":core:network")
include(":core:data")
include(":core:designsystem")
include(":feature:notification")
include(":feature:home")
include(":feature:search")
