package failfast

import strikt.api.expectThat
import strikt.assertions.all
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isFalse
import strikt.assertions.isLessThan
import strikt.assertions.isTrue
import java.lang.management.ManagementFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

fun main() {
    val testFinished = CompletableFuture<Unit>()
    val failingTestFinished = CompletableFuture<Throwable>()
    val results =
        Suite {
            test("firstTest") {
                expectThat(true).isTrue()
                testFinished.complete(Unit)
            }
            test("failing test") {
                try {
                    expectThat(true).isFalse()
                } catch (e: AssertionError) {
                    failingTestFinished.complete(e)
                    throw e
                }
            }
            context("child context") {
                context("grandchild context") {
                    test("failing test") {
                        expectThat(true).isFalse()
                    }
                }
            }
        }.run()
    expectThat(results) {
        get(SuiteResult::allOk).isFalse()
        get(SuiteResult::failedTests).hasSize(2)
            .all {
                get(TestPlusResult::test).get(TestDescription::testName).isEqualTo("failing test")
                get { result }.isA<Failed>().get { failure }.isEqualTo(failingTestFinished.get())
            }
        get(SuiteResult::allTests).hasSize(3)
    }
    testFinished.get(1, TimeUnit.SECONDS)
    println("bootstrapped after: ${uptime()}ms")

    FailFast.runAllTests(true)

    expectThat(ManagementFactory.getRuntimeMXBean().uptime).isLessThan(1000) // lets see how far we can get with one second
}
