[![Download](https://api.bintray.com/packages/christophsturm/maven/failfast/images/download.svg)](https://bintray.com/christophsturm/maven/failfast/_latestVersion)
[![Github CI](https://github.com/christophsturm/failfast/workflows/CI/badge.svg)](https://github.com/christophsturm/failfast/actions)

# Failfast

multithreaded test runner for kotlin focusing on simplicity and speed.

## goals / features

* spec syntax implemented to work just [as expected](https://en.wikipedia.org/wiki/Principle_of_least_astonishment)
* speed and parallel execution.  (own test suite runs in < 1 second)
* configuration via api. just create a class with a main method vor each test configuration, and run it in gradle or idea
* run your tests so fast that you can run all the tests on every change
* autotest to run only changed tests (or tests that test changed classes (planned))  
* really simple. no dependencies and not a lot of code
* pitest plugin (see the build file)

## how it looks like

```kotlin
object FailFastTest {
    val context = describe("The test runner") {
        it("supports describe/it syntax") { expectThat(true).isEqualTo(true) }
        describe("nested contexts") {
            it("can contain tests too") { expectThat(true).isEqualTo(true) }

            describe("disabled/pending tests") {
                itWill("itWill can be used to mark pending tests") {}
                itWill("for pending tests the test body is optional")
            }
            context("context/test syntax is also supported") {
                test( "i prefer describe/it but if there is no subject to describe I use context/test"
                ) {}
            }

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
        }
    }
}

```

to see it in action check out the failfast-example project or a project that uses failfast, for example
[the r2dbcfun test suite](https://github.com/christophsturm/r2dbcfun/blob/main/src/test/kotlin/r2dbcfun/test/AllTests.kt)

## running the test suite

to run FailFast's test suite just run `./gradlew check` or if you want to run it via idea just run
the `FailFastBootstrap.kt` class.

## gradle build

```kotlin
repositories {
    jcenter()
    mavenCentral()
}

dependencies {
    testImplementation("com.christophsturm:failfast:0.1.1")
}
```

## running

Currently, there is no gradle plugin and no idea plugin. Just create a main method in your test sources

```kotlin
fun main() {
  // this will find tests in all files named *Test in the same source root as the main class
  runAllTests()
}

```
alternatively you can also just manually list all test contexts:
```kotlin
fun main() {
  Suite.fromContexts(listOf(MyTest.context, MyOtherTest.context)).run().check()
}
```


or all test classes (possibly slightly faster than listing contexts, because more work is done in parallel)
```kotlin
fun main() {
  Suite.fromClasses(listOf(MyTest::class, MyOtherTest::class)).run().check()
}
```

then add a gradle task file that calls it with the test classpath:

```kotlin
val testMain =
    task("testMain", JavaExec::class) {
        main = "<my-package>.FailFastMainKt"
        classpath = sourceSets["test"].runtimeClasspath
    }

tasks.check { dependsOn(testMain) }
```

`./gradlew check` will then run the tests.

you can also skip test detection and just create a suite from a list of root contexts. Look at the test suite for more
info.

## test lifecycle

Just declare your dependencies in the context blocks. they will be recreated for every test. it just works as expected.
I think ScalaTest has a mode that works like that and kotest also supports it, and calls
it  [instance per leaf](https://github.com/kotest/kotest/blob/master/doc/isolation_mode.md#instanceperleaf)

It combines the power of a dsl with the simplicity of junit 4.

this is from the test isolation unit test:

```kotlin
    val context =
    describe("test dependencies") {
        it("are recreated for each test") {

            // the total order of events is not defined because tests run in parallel.
            // so we track events in a list of a list and record the events that lead to each test execution
            val totalEvents = mutableListOf<List<String>>()
            Suite {
                val testEvents = mutableListOf<String>()
                totalEvents.add(testEvents)
                testEvents.add(ROOT_CONTEXT_EXECUTED)
                autoClose("dependency", closeFunction = { testEvents.add(DEPENDENCY_CLOSED) })
                test("test 1") { testEvents.add(TEST_1_EXECUTED) }
                test("test 2") { testEvents.add(TEST_2_EXECUTED) }
                context("context 1") {
                    testEvents.add(CONTEXT_1_EXECUTED)

                    context("context 2") {
                        testEvents.add(CONTEXT_2_EXECUTED)
                        test("test 3") { testEvents.add(TEST_3_EXECUTED) }
                    }
                }
                test("test4: tests can be defined after contexts") {
                    testEvents.add(TEST_4_EXECUTED)
                }
            }.run()

            expectThat(totalEvents)
                .containsExactlyInAnyOrder(
                    listOf(ROOT_CONTEXT_EXECUTED, TEST_1_EXECUTED, DEPENDENCY_CLOSED),
                    listOf(ROOT_CONTEXT_EXECUTED, TEST_2_EXECUTED, DEPENDENCY_CLOSED),
                    listOf(
                        ROOT_CONTEXT_EXECUTED,
                        CONTEXT_1_EXECUTED,
                        CONTEXT_2_EXECUTED,
                        TEST_3_EXECUTED,
                        DEPENDENCY_CLOSED
                    ),
                    listOf(ROOT_CONTEXT_EXECUTED, TEST_4_EXECUTED, DEPENDENCY_CLOSED)
                )
        }
    }
```

## failfast?

It's pretty fast. its own test suite runs in less than one second:

```kotlin
    val uptime = ManagementFactory.getRuntimeMXBean().uptime
expectThat(uptime).isLessThan(1000) // lets see how far we can get with one second
```


## autotest

add a main method that just runs autotest:

```kotlin
fun main() {
  autoTest()
}
```

create a gradle exec task for it:

```kotlin
task("autotest", JavaExec::class) {
    main = "failfast.AutoTestMainKt"
    classpath = sourceSets["test"].runtimeClasspath
}
```

run it with `./gradlew -t autotest`anytime a test file is recompiled it will run. This works pretty well, but it's not
perfect, because not every change to a tested class triggers a recompile of the test class. Fixing this by reading
dependencies from the test classes' constant pool is on the road map.

## even faster tests, best practices

* avoid heavyweight dependencies. the failfast test suite runs in < 1000ms. that's a lot of time for a computer, and a
  great target for your test suite. slow tests are a code smell. An unexpected example for a heavyweight dependency is
  mockk, it takes about 2 seconds at first invocation.


* spin up slow dependencies at start-up in a separate thread (if you have to use them). for example this is the main
  test method of another open source project of mine:

```kotlin
  fun main() {
    thread {
        JvmMockKGateway() // mockk takes 2 seconds at its first invocation
    }
    if (!H2_ONLY)
        thread {
            postgresqlcontainer // spin up the postgres container if needed
        }
    Suite.fromClasses(findTestClasses(TransactionFunctionalTest::class)).run().check()
}
```

## Test coverage

You can get a quick overview of your test coverage by running your test main method with the idea coverage runner.

There is also a pitest plugin if you want to measure mutation coverage, see the example project for configuration.

## Avoiding Global State

* if you need a web server run it on a random port.
* if you need a database create a db with a random name for each
  test. [like here](https://github.com/christophsturm/r2dbcfun/blob/main/src/test/kotlin/r2dbcfun/test/TestUtil.kt#L18)
  (or run the test in a transaction that is rolled back at the end)
