package failfast.internal

import failfast.*
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.*

object ContextExecutorTest {
    private var assertionError: AssertionError? = null
    val context =
        describe(ContextExecutor::class) {
            val rootContext = Context("root context", null)
            val context1 = Context("context 1", rootContext)
            val context2 = Context("context 2", context1)
            val context4 = Context("context 4", rootContext)
            describe("with a valid root context") {
                val ctx =
                    RootContext("root context") {
                        test("test 1") {}
                        test("test 2") {}
                        test("failed test") {
                            assertionError = AssertionError("failed")
                            throw assertionError!!
                        }
                        context("context 1")
                        {
                            context("context 2")
                            { test("test 3") {} }
                        }
                        context("context 4") { test("test 4") {} }
                    }

                val contextInfo = coroutineScope {
                    ContextExecutor(ctx, this, listener = NullExecutionListener).execute()
                }
                it("returns tests in the same order as they are declared in the file") {
                    expectThat(contextInfo.tests.keys).map { it.testName }
                        .containsExactly(
                            "test 1",
                            "test 2",
                            "failed test",
                            "test 3",
                            "test 4"
                        )
                }
                it("returns deferred test results") {
                    val testResults = contextInfo.tests.values.awaitAll()
                    val successful = testResults.filterIsInstance<Success>()
                    val failed = testResults - successful
                    expectThat(successful.map { it.test.testName })
                        .containsExactly(
                            "test 1",
                            "test 2",
                            "test 3",
                            "test 4"
                        )
                    expectThat(failed).map { it.test.testName }.containsExactly("failed test")
                }

                it("returns contexts in the same order as they appear in the file") {
                    expectThat(contextInfo.contexts)
                        .containsExactly(rootContext, context1, context2, context4)
                }
                it("reports time of successful tests") {
                    expectThat(contextInfo.tests.values.awaitAll().filterIsInstance<Success>())
                        .all { get { timeMicro }.isGreaterThan(1) }
                }
                it("reports file name and line number for all tests") {
                    expectThat(contextInfo.tests.keys).all {
                        get { stackTraceElement }.and {
                            get { fileName }.isEqualTo("ContextExecutorTest.kt")
                            get { lineNumber }.isGreaterThan(1)
                        }
                    }
                }
            }
            describe("supports lazy execution") {
                it("postpones test execution until the deferred is awaited when lazy is set to true") {
                    var testExecuted = false
                    val ctx =
                        RootContext("root context") {
                            test("test 1") {
                                testExecuted = true
                            }
                        }
                    coroutineScope {
                        val contextInfo =
                            ContextExecutor(ctx, this, lazy = true, listener = NullExecutionListener).execute()
                        expectThat(testExecuted).isEqualTo(false)
                        val deferred = contextInfo.tests.values.single()
                        expectThat(deferred.await()).isA<Success>()
                        expectThat(testExecuted).isEqualTo(true)
                    }

                }
            }

            describe("handles failing contexts")
            {
                var runtimeException: RuntimeException? = null

                val ctx =
                    RootContext("root context") {
                        test("test 1") {}
                        test("test 2") {}
                        context("context 1") {
                            runtimeException = RuntimeException("oops context creation failed")
                            throw runtimeException!!
                        }
                        context("context 4") { test("test 4") {} }
                    }
                val results = coroutineScope {
                    ContextExecutor(ctx, this, listener = NullExecutionListener).execute()
                }
                it("reports a failing context as a failing test") {
                    expectThat(results.tests.values.awaitAll().filterIsInstance<Failed>()).single().and {
                        get { test }.and {
                            get<String> { testName }.isEqualTo("context 1")
                            get<Context> { parentContext }.isEqualTo(rootContext)
                            get { stackTraceElement.toString() }.endsWith(
                                "ContextExecutorTest.kt:${
                                    getLineNumber(
                                        runtimeException
                                    ) - 1
                                })"
                            )
                        }
                    }
                }
                it("does not report a failing context as a context") {
                    expectThat(results.contexts).doesNotContain(context1)
                }
            }
            describe("detects duplicated tests")
            {
                it("fails with duplicate tests in one context") {
                    val ctx =
                        RootContext {
                            test("duplicate test name") {}
                            test("duplicate test name") {}
                        }
                    coroutineScope {
                        expectThrows<FailFastException> {
                            ContextExecutor(
                                ctx,
                                this,
                                listener = NullExecutionListener
                            ).execute()
                        }
                    }
                }
                it("does not fail when the tests with the same name are in different contexts") {
                    val ctx =
                        RootContext {
                            test("duplicate test name") {}
                            context("context") { test("duplicate test name") {} }
                        }
                    coroutineScope { ContextExecutor(ctx, this, listener = NullExecutionListener).execute() }
                }
            }
            describe("detects duplicate contexts") {
                it("fails with duplicate contexts in one context") {
                    val ctx =
                        RootContext {
                            context("duplicate test name") {}
                            context("duplicate test name") {}
                        }
                    coroutineScope {
                        expectThrows<FailFastException> {
                            ContextExecutor(
                                ctx,
                                this,
                                listener = NullExecutionListener
                            ).execute()
                        }
                    }
                }
                it("does not fail when the contexts with the same name are in different contexts") {
                    val ctx =
                        RootContext {
                            test("same context name") {}
                            context("context") { test("same context name") {} }
                        }
                    coroutineScope { ContextExecutor(ctx, this, listener = NullExecutionListener).execute() }
                }
                it("fails when a context has the same name as a test in the same contexts") {
                    val ctx =
                        RootContext {
                            test("same name") {}
                            context("same name") {}
                        }
                    coroutineScope {
                        expectThrows<FailFastException> {
                            ContextExecutor(
                                ctx,
                                this,
                                listener = NullExecutionListener
                            ).execute()
                        }
                    }
                }

            }
            it("can close resources")
            {
                val events = mutableListOf<String>()
                var closeCalled = false
                val closable = AutoCloseable { closeCalled = true }
                var resource: AutoCloseable? = null
                Suite {
                    resource = autoClose(closable) {
                        it.close()
                        events.add("close callback")
                    }
                    test("a test") { events.add("test") }
                }.run(silent = true)
                expectThat(events).containsExactly("test", "close callback")
                expectThat(resource).isEqualTo(closable)
                expectThat(closeCalled).isTrue()
            }
        }

    private fun getLineNumber(runtimeException: Throwable?): Int =
        runtimeException!!.stackTrace.first().lineNumber
}
