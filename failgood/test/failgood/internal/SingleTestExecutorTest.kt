package failgood.internal

import failgood.Context
import failgood.Failure
import failgood.SourceInfo
import failgood.Success
import failgood.Test
import failgood.TestCollection
import failgood.TestDescription
import failgood.dsl.ContextFunction
import failgood.mock.mock
import failgood.testCollection
import kotlin.test.assertEquals
import kotlinx.coroutines.coroutineScope
import strikt.api.expectThat
import strikt.assertions.containsExactly
import strikt.assertions.isA
import strikt.assertions.isEqualTo

@Test
class SingleTestExecutorTest {
    val tests =
        testCollection(SingleTestExecutor::class) {
            val testDSL =
                TestContext(
                    mock(),
                    mock(),
                    TestDescription(Context("root"), "blah", SourceInfo("", "", 0)),
                    Unit)
            val resourceCloser = coroutineScope { ResourceCloserImpl(this) }

            val events = mutableListOf<String>()
            describe("test execution") {
                describe("a context without given") {
                    val ctx: ContextFunction = {
                        events.add("root context")
                        test("test 1") { events.add("test 1") }
                        test("test 2") { events.add("test 2") }
                        context("context 1") {
                            events.add("context 1")

                            context("context 2") {
                                events.add("context 2")
                                test("test 3") { events.add("test 3") }
                            }
                        }
                    }
                    val rootContext = Context("root context", null)
                    val context1 = Context("context 1", rootContext)
                    val context2 = Context("context 2", context1)

                    it("executes a single test") {
                        val result =
                            SingleTestExecutor(
                                    ContextPath(rootContext, "test 1"),
                                    testDSL,
                                    resourceCloser,
                                    ctx) {}
                                .execute()
                        expectThat(events).containsExactly("root context", "test 1")
                        expectThat(result).isA<Success>()
                    }
                    it("executes a nested single test") {
                        val result =
                            SingleTestExecutor(
                                    ContextPath(context2, "test 3"),
                                    testDSL,
                                    resourceCloser,
                                    ctx) {}
                                .execute()
                        expectThat(events)
                            .containsExactly("root context", "context 1", "context 2", "test 3")
                        expectThat(result).isA<Success>()
                    }
                }
                describe("a context with given") {
                    val ctx: ContextFunction = {
                        events.add("root context")
                        test("test 1") { events.add("test 1") }
                        test("test 2") { events.add("test 2") }
                        context("context 1", given = { "context 1 given" }) {
                            events.add("context 1")

                            context("context 2", given = { given() + " context 2 given" }) {
                                events.add("context 2")
                                test("test 3") { events.add("test 3:$given") }
                            }
                        }
                    }
                    val rootContext = Context("root context", null)
                    val context1 = Context("context 1", rootContext)
                    val context2 = Context("context 2", context1)

                    it("collects given information") {
                        val result =
                            SingleTestExecutor(
                                    ContextPath(context2, "test 3"),
                                    testDSL,
                                    resourceCloser,
                                    ctx) {}
                                .execute()
                        assert(result is Success)
                        assertEquals(
                            listOf(
                                "root context",
                                "context 1",
                                "context 2",
                                "test 3:context 1 given context 2 given"),
                            events)
                    }
                }
            }
            it("also supports describe / it") {
                val context: ContextFunction = {
                    describe("with a valid root context") {
                        it("returns number of tests") {}
                        it("returns contexts") {}
                    }
                }
                val test =
                    ContextPath(
                        Context(
                            "with a valid root context", Context("TestCollectionExecutor", null)),
                        "returns contexts")
                val executor = SingleTestExecutor(test, testDSL, resourceCloser, context) {}
                executor.execute()
            }
            describe("error handling") {
                it("reports exceptions in the context as test failures") {
                    val runtimeException = RuntimeException()
                    val contextThatThrows =
                        TestCollection("root context") { throw runtimeException }
                    val result =
                        SingleTestExecutor(
                                ContextPath(Context("root context", null), "test"),
                                testDSL,
                                resourceCloser,
                                contextThatThrows.function) {}
                            .execute()
                    expectThat(result).isA<Failure>().get { failure }.isEqualTo(runtimeException)
                }
                it("reports exceptions in the autoclose function as test failures") {
                    val runtimeException = RuntimeException()
                    val contextThatThrows =
                        TestCollection("root context") {
                            autoClose("String") { throw runtimeException }
                            it("test") {}
                        }
                    val result =
                        SingleTestExecutor(
                                ContextPath(Context("root context", null), "test"),
                                testDSL,
                                resourceCloser,
                                contextThatThrows.function) {}
                            .execute()
                    expectThat(result).isA<Failure>().get { failure }.isEqualTo(runtimeException)
                }
            }
        }
}
