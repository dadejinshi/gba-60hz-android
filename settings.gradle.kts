pluginManagement {
    repositories {
        // 官方仓库优先：GitHub Actions 跑在境外，先走阿里云镜像反而容易拉不动而失败
        google()
        mavenCentral()
        gradlePluginPortal()
        // 国内镜像兜底（本机在国内打包且官方仓库慢时，可把这几行挪到最前面）
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 国内镜像兜底
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "GBA60Hz"
include(":app")
