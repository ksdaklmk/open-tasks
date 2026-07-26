pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OpenTasks"

include(
    ":app",
    ":core:model",
    ":core:domain",
    ":core:data",
    ":core:crypto",
    ":core:sync",
    ":core:designsystem",
    ":feature:home",
    ":feature:tasks",
    ":feature:projects",
    ":feature:schedule",
    ":feature:more",
)
