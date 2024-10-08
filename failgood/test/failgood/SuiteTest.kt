package failgood

import failgood.internal.FailedTestCollectionExecution
import failgood.mock.mock
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import strikt.api.expectThat
import strikt.api.expectThrows
import strikt.assertions.isEqualTo

@Test
class SuiteTest {
    val tests =
        testCollection(Suite::class) {
            test("Empty Suite fails") {
                expectThrows<RuntimeException> { Suite(listOf<ContextProvider>()) }
            }
            test("Suite {} creates a root context") {
                expectThat(
                        Suite { test("test") {} }
                            .contextProviders
                            .single()
                            .getContexts()
                            .single()
                            .rootContext
                            .name)
                    .isEqualTo("root")
            }
            describe("coroutine scope") {
                it("does not wait for tests before returning context info") {
                    val contexts =
                        (1..10).map {
                            TestCollection("root context") {
                                repeat(10) { test("test $it") { delay(1000) } }
                            }
                        }
                    val scope = CoroutineScope(Dispatchers.Unconfined)
                    val deferredResult =
                        withTimeout(100) { Suite(contexts).findAndStartTests(scope) }
                    withTimeout(100) { deferredResult.awaitAll() }
                    scope.cancel()
                }
            }
            describe("error handling") {
                it("treats errors in getContexts as failed context") {
                    class MyErrorTest

                    val scope = CoroutineScope(Dispatchers.Unconfined)
                    val objectContextProvider =
                        mock<ContextProvider> {
                            method { getContexts() }
                                .will {
                                    throw ErrorLoadingContextsFromClass(
                                        "the error",
                                        MyErrorTest::class,
                                        RuntimeException("exception error"))
                                }
                        }

                    val contextResult =
                        assertNotNull(
                            Suite(listOf(objectContextProvider))
                                .findAndStartTests(scope)
                                .singleOrNull()
                                ?.await())
                    assert(
                        contextResult is FailedTestCollectionExecution &&
                            (contextResult.failure.message == "the error" &&
                                contextResult.context.name == MyErrorTest::class.simpleName))
                }
            }
            describe("timeout parsing") {
                it("returns timeout when it is a number") {
                    assert(Suite.parseTimeout("123") == 123L)
                }
                it("throws when it is not a number") {
                    assertNotNull(runCatching { Suite.parseTimeout("BLAH") }.exceptionOrNull())
                }
                it("returns DEFAULT_TIMEOUT when no timeout is set") {
                    assert(Suite.parseTimeout(null) == DEFAULT_TIMEOUT)
                }
            }
        }
}
