package failgood.internal.execution

import failgood.Context
import failgood.ExecutionListener
import failgood.Failure
import failgood.Ignored.Because
import failgood.NullExecutionListener
import failgood.Skipped
import failgood.Success
import failgood.Test
import failgood.TestCollection
import failgood.TestDescription
import failgood.internal.ExecuteAllTests
import failgood.internal.FailedTestCollectionExecution
import failgood.internal.StringListTestFilter
import failgood.internal.TestCollectionExecutionResult
import failgood.internal.TestFilter
import failgood.internal.TestResults
import failgood.internal.execution.DiscoveryListener.Event
import failgood.internal.execution.DiscoveryListener.Type.CONTEXT_DISCOVERED
import failgood.internal.execution.DiscoveryListener.Type.TEST_DISCOVERED
import failgood.testCollection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import strikt.api.expectThat
import strikt.assertions.all
import strikt.assertions.contains
import strikt.assertions.containsExactly
import strikt.assertions.containsExactlyInAnyOrder
import strikt.assertions.get
import strikt.assertions.isA
import strikt.assertions.isEmpty
import strikt.assertions.isEqualTo
import strikt.assertions.isGreaterThanOrEqualTo
import strikt.assertions.isNotEmpty
import strikt.assertions.isNotNull
import strikt.assertions.map
import strikt.assertions.message

@Test
object TestCollectionExecutorTest {
    val assertionError: java.lang.AssertionError = AssertionError("failed")

    class TypicalTestContext {
        val context: TestCollection<Unit> =
            TestCollection("root context") {
                test("test 1") { delay(1) }
                test("test 2") { delay(1) }
                test("ignored test", ignored = Because("testing")) {}
                test("failed test") { throw assertionError }
                context("context 1") {
                    test("context 1 test") {}
                    // comment to make sure that context1 and context2 are not on the same
                    // line
                    context("context 2") { test("test 3") { delay(1) } }
                }
                context("context 3") { test("test 4") { delay(1) } }
            }

        suspend fun execute(
            tag: String? = null,
            listener: ExecutionListener = NullExecutionListener,
            testFilter: TestFilter = ExecuteAllTests,
        ): TestCollectionExecutionResult {
            return coroutineScope {
                TestCollectionExecutor(
                        context,
                        this,
                        runOnlyTag = tag,
                        listener = listener,
                        testFilter = testFilter)
                    .execute()
            }
        }
    }

    private suspend fun executedTestContext() =
        assertNotNull(TypicalTestContext().execute() as? TestResults)

    @Suppress("SimplifiableCallChain") // for better kotlin-power-assert output
    val tests = testCollection {
        describe("with a typical valid root context") {
            describe("executing all the tests", given = { executedTestContext() }) {
                it("returns tests in the same order as they are declared in the file") {
                    assert(
                        given.tests.keys.map { it.testName } ==
                            listOf(
                                "test 1",
                                "test 2",
                                "ignored test",
                                "failed test",
                                "context 1 test",
                                "test 3",
                                "test 4"))
                }
                it("returns deferred test results") {
                    val testResults = given.tests.values.awaitAll()
                    val successful = testResults.filter { it.isSuccess }
                    val failed = assertNotNull(testResults.filter { it.isFailure }.singleOrNull())
                    val skipped = assertNotNull(testResults.filter { it.isSkipped }.singleOrNull())
                    assert(
                        successful.map { it.test.testName } ==
                            listOf("test 1", "test 2", "context 1 test", "test 3", "test 4"))
                    assert(failed.test.testName == "failed test")
                    assert(skipped.test.testName == "ignored test")
                }

                it("returns contexts in the same order as they appear in the file") {
                    expectThat(given.contexts)
                        .map { it.name }
                        .containsExactly("root context", "context 1", "context 2", "context 3")
                }
                it("reports time of successful tests") {
                    expectThat(
                            given.tests.values
                                .awaitAll()
                                .map { it.result }
                                .filterIsInstance<Success>())
                        .isNotEmpty()
                        .all { get { timeMicro }.isGreaterThanOrEqualTo(1) }
                }

                describe(
                    "reports failed tests",
                    given = {
                        given()
                            .tests
                            .values
                            .awaitAll()
                            .map { it.result }
                            .filterIsInstance<Failure>()
                            .single()
                    }) {
                        it("reports exception for failed tests") {
                            assertEquals(
                                given.failure.stackTraceToString(),
                                assertionError.stackTraceToString())
                        }
                    }
            }
            describe(
                "with a listener",
                given = {
                    val listener = DiscoveryListener()
                    assertNotNull(
                        assertNotNull(
                            TypicalTestContext().execute(listener = listener) as? TestResults))
                    listener
                }) {
                    it("reports tests and contexts in their order inside the file") {
                        assertEquals(
                            listOf(
                                Event(CONTEXT_DISCOVERED, "root context"),
                                Event(TEST_DISCOVERED, "test 1"),
                                Event(TEST_DISCOVERED, "test 2"),
                                Event(TEST_DISCOVERED, "ignored test"),
                                Event(TEST_DISCOVERED, "failed test"),
                                Event(CONTEXT_DISCOVERED, "context 1"),
                                Event(TEST_DISCOVERED, "context 1 test"),
                                Event(CONTEXT_DISCOVERED, "context 2"),
                                Event(TEST_DISCOVERED, "test 3"),
                                Event(CONTEXT_DISCOVERED, "context 3"),
                                Event(TEST_DISCOVERED, "test 4"),
                            ),
                            given.events)
                    }
                }
            describe("executing a subset of tests", given = { TypicalTestContext() }) {
                it("can execute a subset of tests") {
                    val contextResult =
                        given.execute(
                            testFilter = StringListTestFilter(listOf("root context", "test 1")))
                    val testResults = expectThat(contextResult).isA<TestResults>().subject
                    expectThat(testResults) {
                        get { tests.keys }.map { it.testName }.containsExactly("test 1")
                        get { contexts }.map { it.name }.containsExactly("root context")
                    }
                }
                it("does not execute the context at all if the root name does not match") {
                    val contextResult =
                        given.execute(
                            testFilter =
                                StringListTestFilter(listOf("other root context", "test 1")))
                    val testResults = expectThat(contextResult).isA<TestResults>().subject
                    expectThat(testResults) {
                        get { tests }.isEmpty()
                        get { contexts }.isEmpty()
                    }
                }
            }
            describe("reports line numbers") {
                var rootContextLine = 0
                var context1Line = 0
                var context2Line = 0
                var test1Line = 0
                var test2Line = 0
                val ctx =
                    TestCollection("root context") {
                        rootContextLine = RuntimeException().stackTrace.first().lineNumber - 1
                        describe("context 1") {
                            context1Line = RuntimeException().stackTrace.first().lineNumber - 1
                            it("test1") {
                                test1Line = RuntimeException().stackTrace.first().lineNumber - 1
                            }
                        }
                        describe("context 2") {
                            context2Line = RuntimeException().stackTrace.first().lineNumber - 1
                            it("test2") {
                                test2Line = RuntimeException().stackTrace.first().lineNumber - 1
                            }
                        }
                    }
                val testResults = assertNotNull(execute(ctx) as? TestResults)
                it("returns file info for all subcontexts") {
                    expectThat(testResults.contexts).all {
                        get { sourceInfo }
                            .isNotNull()
                            .and { get { fileName }.isEqualTo("TestCollectionExecutorTest.kt") }
                    }
                }
                it("returns line number for contexts") {
                    expectThat(testResults.contexts) {
                        get(0)
                            .get { sourceInfo }
                            .isNotNull()
                            .get { lineNumber }
                            .isEqualTo(rootContextLine)
                        get(1)
                            .get { sourceInfo }
                            .isNotNull()
                            .get { lineNumber }
                            .isEqualTo(context1Line)
                        get(2)
                            .get { sourceInfo }
                            .isNotNull()
                            .get { lineNumber }
                            .isEqualTo(context2Line)
                    }
                }
                it("reports file name for all tests") {
                    expectThat(testResults.tests.keys).all {
                        get { sourceInfo }
                            .and { get { fileName }.isEqualTo("TestCollectionExecutorTest.kt") }
                    }
                }
                it("reports line number for all tests") {
                    expectThat(testResults.tests.keys.toList()) {
                        get(0).get { sourceInfo }.get { lineNumber }.isEqualTo(test1Line)
                        get(1).get { sourceInfo }.get { lineNumber }.isEqualTo(test2Line)
                    }
                }
            }
        }
        describe("ignored tests") {
            it("are reported") {
                val result =
                    execute(
                        TestCollection("root context") {
                            it(
                                "contains a single ignored test",
                                ignored = Because("testing ignoring")) {}
                        })
                val testResults = assertNotNull(result as? TestResults)
                val test = assertNotNull(testResults.tests.keys.singleOrNull())
                assert(test.testName == "contains a single ignored test")
            }
        }
        describe("supports lazy execution") {
            it("postpones test execution until the deferred is awaited when lazy is set to true") {
                var testExecuted = false
                val ctx = TestCollection("root context") { test("test 1") { testExecuted = true } }
                coroutineScope {
                    val contextInfo =
                        TestCollectionExecutor(ctx, this, lazy = true, testFilter = ExecuteAllTests)
                            .execute()

                    expectThat(testExecuted).isEqualTo(false)
                    expectThat(contextInfo).isA<TestResults>()
                    val deferred = (contextInfo as TestResults).tests.values.single()
                    expectThat(deferred.await().result).isA<Success>()
                    expectThat(testExecuted).isEqualTo(true)
                }
            }
        }
        describe("timing") {
            it("reports context structure before tests finish") {
                val ctx =
                    TestCollection("root context") {
                        repeat(10) { test("test $it") { delay(2000) } }
                    }
                val scope = CoroutineScope(Dispatchers.Unconfined)
                // this timeout is huge because of slow ci, that does not mean it takes 1 second
                // in normal use
                withTimeout(1000) {
                    assert(TestCollectionExecutor(ctx, scope).execute() is TestResults)
                }
                scope.cancel()
            }
        }

        describe("when an exception happens inside a subcontext") {
            var error: Throwable? = null

            val ctx =
                TestCollection("root context") {
                    test("test 1") {}
                    test("test 2") {}
                    context("context 1") {
                        error = NotImplementedError("")
                        throw error!!
                    }
                    context("context 4") { test("test 4") {} }
                }
            val results = assertNotNull(execute(ctx) as? TestResults)

            it("it is reported as a failing test inside that context") {
                val failures = results.tests.values.awaitAll().filter { it.isFailure }
                val singleFailure = assertNotNull(failures.singleOrNull())
                // candidate for a soft assert
                with(singleFailure.test) {
                    assert(testName == "error in context")
                    assert(context.name == "context 1")
                    with(sourceInfo) {
                        assert(lineNumber == getLineNumber(error) - 1)
                        assert(className.contains("TestCollectionExecutorTest"))
                    }
                }
            }
            it("reports the context as a context") {
                assert(results.contexts.map { it.name }.contains("context 1"))
            }
        }
        describe("handling of special cases") {
            describe("strange contexts") {
                it("a context with only one ignored test") {
                    val context = TestCollection {
                        describe("context") {
                            it("pending", ignored = Because("testing a pending test")) {}
                        }
                        test("test") {}
                    }
                    val contextResult = execute(context)
                    assert(
                        contextResult is TestResults &&
                            contextResult.tests.values.awaitAll().all { !it.isFailure })
                }
                test("tests can not contain nested contexts") {
                    // context("this does not even compile") {}
                }
            }

            describe("an ignored sub-context") {
                val ctx =
                    TestCollection("root context") {
                        test("test 1") {}
                        test("test 2") {}
                        context(
                            "context 1",
                            ignored = Because("We are testing that it is correctly reported")) {}
                        context("context 4") { test("test 4") {} }
                    }
                val results = assertNotNull(execute(ctx) as? TestResults)

                it("reports the context as a context") {
                    assert(results.contexts.map { it.name }.contains("context 1"))
                }
                it("it reports it as a skipped test inside that context") {
                    val skippedTest = results.tests.values.awaitAll().filter { it.isSkipped }
                    val singleSkippedTest = assertNotNull(skippedTest.singleOrNull())
                    // candidate for a soft assert
                    with(singleSkippedTest.test) {
                        assert(
                            testName ==
                                "context ignored because We are testing that it is correctly reported")
                        assert(context.name == "context 1")
                        assert(sourceInfo.className.contains("TestCollectionExecutorTest"))
                    }
                    assert(
                        (singleSkippedTest.result as Skipped).reason ==
                            "We are testing that it is correctly reported")
                }
            }
        }
        it("handles failing root contexts") {
            val ctx =
                TestCollection("root context") { throw RuntimeException("root context failed") }
            assert(execute(ctx) is FailedTestCollectionExecution)
        }
        describe("detects duplicated tests") {
            it("fails with duplicate tests in one context") {
                val ctx = TestCollection {
                    test("dup test name") {}
                    test("dup test name") {}
                }
                val result = execute(ctx)
                assert(
                    result is FailedTestCollectionExecution &&
                        result.failure.message!!.contains(
                            "duplicate name \"dup test name\" in context \"root\""))
            }
            it("does not fail when the tests with the same name are in different contexts") {
                val ctx = TestCollection {
                    test("duplicate test name") {}
                    context("context") { test("duplicate test name") {} }
                }
                coroutineScope {
                    TestCollectionExecutor(ctx, this, testFilter = ExecuteAllTests).execute()
                }
            }
        }
        describe("detects duplicate contexts") {
            it("fails with duplicate contexts in one context") {
                val ctx = TestCollection {
                    context("dup ctx") {}
                    context("dup ctx") {}
                }
                val result = execute(ctx)
                expectThat(result)
                    .isA<FailedTestCollectionExecution>()
                    .get { failure }
                    .message
                    .isNotNull()
                    .contains("duplicate name \"dup ctx\" in context \"root\"")
            }
            it("does not fail when the contexts with the same name are in different contexts") {
                val ctx = TestCollection {
                    test("same context name") {}
                    context("context") { test("same context name") {} }
                }
                coroutineScope {
                    TestCollectionExecutor(ctx, this, testFilter = ExecuteAllTests).execute()
                }
            }
            it("fails when a context has the same name as a test in the same contexts") {
                val ctx = TestCollection {
                    test("same name") {}
                    context("same name") {}
                }
                val result = execute(ctx)
                expectThat(result)
                    .isA<FailedTestCollectionExecution>()
                    .get { failure }
                    .message
                    .isNotNull()
                    .contains("duplicate name \"same name\" in context \"root\"")
            }
        }
        describe("filtering by tag") {
            val events = ConcurrentHashMap.newKeySet<String>()

            // start with the easy version
            it("can filter contexts in the root context by tag") {
                val context = TestCollection {
                    describe("context without the tag") { events.add("context without tag") }
                    describe("context with the tag", tags = setOf("single")) {
                        events.add("context with tag")
                        it("should be executed") { events.add("test in context with tag") }
                        describe("subcontext of the context with the tag") {
                            events.add("context in context with tag")
                            it("should also be executed") {
                                events.add("test in context in context with tag")
                            }
                        }
                    }
                    test("test that should also not be executed") {
                        events.add("test in root context without tag")
                    }
                }
                val contextResult = execute(context, "single")
                expectSuccess(contextResult)
                expectThat(events)
                    .containsExactlyInAnyOrder(
                        "context with tag",
                        "test in context with tag",
                        "context in context with tag",
                        "test in context in context with tag")
            }
            it("can filter tests in the root context by tag") {
                val context = TestCollection {
                    describe("context without the tag") { events.add("context without tag") }
                    test("test with the tag", tags = setOf("single")) {
                        events.add("test in root context with tag")
                    }
                    it("other test with the tag", tags = setOf("single")) {
                        events.add("other test with the tag")
                    }
                    test("test that should also not be executed") {
                        events.add("test in root context without tag")
                    }
                }
                val contextResult = execute(context, tag = "single")
                expectSuccess(contextResult)
                expectThat(events)
                    .containsExactlyInAnyOrder(
                        "test in root context with tag", "other test with the tag")
            }
            it(
                "can filter tests in a subcontext",
                ignored =
                    Because(
                        "This can currently not work " +
                            "because we don't find the tests in the subcontext without executing the context")) {
                    val context = TestCollection {
                        describe("context without the tag") {
                            events.add("context without tag")
                            it("test in context without the tag") {
                                events.add("test in context without the tag")
                            }
                        }
                        describe("context without the tag that contains the test with the tag") {
                            events.add(
                                "context without the tag that contains the test with the tag")
                            test("test with the tag", tags = setOf("single")) {
                                events.add("test with the tag")
                            }
                        }
                        test("test in root context without tag") {
                            events.add("test in root context without tag")
                        }
                    }
                    val contextResult = execute(context, "single")
                    expectSuccess(contextResult)
                    expectThat(events)
                        .containsExactlyInAnyOrder(
                            "context without the tag that contains the test with the tag",
                            "test with the tag")
                }
        }
    }

    private suspend fun expectSuccess(
        testCollectionExecutionResult: TestCollectionExecutionResult
    ) {
        assert(
            testCollectionExecutionResult is TestResults &&
                testCollectionExecutionResult.tests.values.awaitAll().all { it.isSuccess })
    }

    private suspend fun execute(
        context: TestCollection<Unit>,
        tag: String? = null,
        listener: ExecutionListener = NullExecutionListener
    ): TestCollectionExecutionResult {
        return coroutineScope {
            TestCollectionExecutor(context, this, runOnlyTag = tag, listener = listener).execute()
        }
    }

    private fun getLineNumber(runtimeException: Throwable?): Int =
        runtimeException!!.stackTrace.first().lineNumber
}

class DiscoveryListener : ExecutionListener {
    enum class Type {
        TEST_DISCOVERED,
        CONTEXT_DISCOVERED /*,
                           TEST_STARTED,
                           FINISHED,
                           TEST_EVENT */
    }

    private fun event(type: Type, name: String) {
        events.add(Event(type, name))
    }

    data class Event(val type: Type, val testDescription: String) {
        override fun toString(): String {
            return "$testDescription $type"
        }
    }

    val events = CopyOnWriteArrayList<Event>()

    override suspend fun testDiscovered(testDescription: TestDescription) {
        event(TEST_DISCOVERED, testDescription.testName)
    }

    override suspend fun contextDiscovered(context: Context) {
        event(CONTEXT_DISCOVERED, context.name)
    }
}
