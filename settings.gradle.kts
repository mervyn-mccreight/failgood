@file:Suppress("UnstableApiUsage")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
//    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS // kover does not work with this
}
rootProject.name = "failgood-root"
include("failgood", "failgood-examples", "failgood-gradle-test",
    "experiments:failgood-debugger",
    "experiments:gradle-plugin"
    )
