[![Maven Central](https://maven-badges.herokuapp.com/maven-central/dev.failgood/failgood/badge.svg)](https://maven-badges.herokuapp.com/maven-central/dev.failgood/failgood)
[![Github CI](https://github.com/failgood/failgood/workflows/CI/badge.svg)](https://github.com/failgood/failgood/actions)

# FailGood

Multi-threaded test runner for Kotlin focusing on simplicity, usability and speed. Now including a simple mock library.
Still zero dependencies.

## Goals / Features

Every design decision is only influenced by what's best for a short test feedback loop, and to make simple things simple
and complex things possible. No feature exists "because that's how JUnit works". Everything is driven by the needs of
people who write tests daily and iterate fast.

* Spec syntax implemented to work just [as expected](https://en.wikipedia.org/wiki/Principle_of_least_astonishment).
* Speed and parallel execution. FailGood's own test suite runs in < 1 second.
* Run your tests so fast that you can run all the tests on every change.
* Autotest to run only changed tests.
* Pitest plugin (see the build file).

## How it looks like

```kotlin
@Test
class FailGoodTest {
    val context = describe("The test runner") {
        it("supports describe/it syntax") { expectThat(true).isEqualTo(true) }
        describe("nested contexts") {
            it("can contain tests too") { expectThat(true).isEqualTo(true) }

            context("dynamic tests") {
                (1 until 5).forEach { contextNr ->
                    context("dynamic context #$contextNr") {
                        (1 until 5).forEach { testNr ->
                            test("test #$testNr") {
                                expectThat(testNr).isLessThan(10)
                                expectThat(contextNr).isLessThan(10)
                            }
                        }
                    }
                }
            }
            describe("disabled/pending tests") {
                pending("pending can be used to mark pending tests") {}
                pending("for pending tests the test body is optional")
            }
            context("context/test syntax is also supported") {
                test(
                    "I prefer describe/it but if there is no subject to describe I use " +
                            "context/test"
                ) {}
            }

    }
  }
}

```

To see it in action check out the failgood-example project, or a project that uses FailGood, for example
[the the.orm test suite](https://github.com/christophsturm/the.orm)
or [the restaurant test suite](https://github.com/christophsturm/restaurant/tree/main/core/src/test/kotlin/restaurant)

## Running the test suite

to run FailGood's test suite just run `./gradlew check` or if you want to run it via idea just run
the `FailGoodBootstrap.kt` class.

## Gradle build

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    testImplementation("dev.failgood:failgood:0.5.2")
}
tasks.test {
    useJUnitPlatform {
        includeEngines("failgood") // this is optional, gradle finds the engine automatically.
    }
}
```

### Running the test

Failgood comes with a JUnit Platform Engine that should make it easy to run Failgood tests with IDEAs integrated test
runner. You can run all tests in a package, or run Single test classes (if they are annotated with failgood.Test)

For best results, select "run tests in IDEA" in your gradle settings, although running in gradle works pretty well
already too

## Test lifecycle

Just declare your dependencies in the context blocks. They will be recreated for every test. It just works as expected.
I think ScalaTest has a mode that works like that and kotest also supports it, and calls
it  [instance per leaf](https://github.com/kotest/kotest/blob/master/doc/isolation_mode.md#instanceperleaf)

It combines the power of a dsl with the simplicity of JUnit 4.

## Autotest

add a main method that just runs autotest:

```kotlin
fun main() {
    autoTest()
}
```

create a gradle exec task for it:

```kotlin
tasks.register("autotest", JavaExec::class) {
    mainClass.set("failgood.AutoTestMainKt")
    classpath = sourceSets["test"].runtimeClasspath
}
```

run it with `./gradlew -t autotest`anytime a test file is recompiled it will run. This works pretty well, but it's not
perfect, because not every change to a tested class triggers a recompile of the test class. Fixing this by reading
dependencies from the test classes' constant pool is on the roadmap.

## Test coverage

Failgood works well with the kover plugin, and if you want real mutation coverage, there is also a pitest plugin.

## Even faster tests; best practices

* avoid heavyweight dependencies. the failgood test suite runs in < 1000ms. That's a lot of time for a computer, and a
  great target for your test suite. Slow tests are a code smell. An unexpected example for a heavyweight dependency is
  mockk, it takes about 2 seconds at first invocation. To avoid that you can use the simple mocking library that comes
  with failgood. (see [MockTest.kt](failgood/src/test/kotlin/failgood/mock/MockTest.kt))


## Avoiding global state

Failgood runs your tests in parallel, so you need to avoid global state.
* if you need a web server run it on a random port.
* if you need a database create a db with a random name for each test. (see the.orm)
  or run the test in a transaction that is rolled back at the end
