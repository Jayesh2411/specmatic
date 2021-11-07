package `in`.specmatic.core.log

import `in`.specmatic.core.pattern.ContractException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class VerboseExceptionLogTest {
    val log = try {
        throw ContractException("test")
    } catch(e: Throwable) {
        VerboseExceptionLog(e, "msg")
    }

    @Test
    fun `verbose exception log text`() {
        assertThat(log.toLogString().trim()).startsWith("msg: test\n")
        assertThat(log.toLogString().trim()).contains("ContractException")
    }

    @Test
    fun `verbose exception log json`() {
        log.toJSONObject().apply {
            assertThat(this.getString("className")).isEqualTo("in.specmatic.core.pattern.ContractException")
            assertThat(this.getString("cause")).isEqualTo("test")
            assertThat(this.getString("message")).isEqualTo("msg")
            assertThat(this.getString("stackTrace")).contains("ContractException")
        }
    }
}