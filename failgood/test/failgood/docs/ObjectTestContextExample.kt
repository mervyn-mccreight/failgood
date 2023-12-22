package failgood.docs

import failgood.Test
import failgood.describe
import failgood.tests
import strikt.api.expectThat
import strikt.assertions.isEqualTo

@Test
object ObjectTestContextExample {
    val context =
        tests("test context defined in a kotlin object") {
            it("describes behavior") { expectThat("test").isEqualTo("test") }
        }
}
