package app.quickerlink.connection

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickerMobilePushProtocolTest {
    @Test
    fun `parses strict text notification and file offers`() {
        assertEquals(
            QuickerMobilePush.Text("hello"),
            parse("""{"protocol":"quickerlink.mobile-push","version":8,"op":"text.enqueue","text":"hello"}"""),
        )
        assertEquals(
            QuickerMobilePush.Notification("计时结束", "休息一下"),
            parse("""{"protocol":"quickerlink.mobile-push","version":8,"op":"notify.enqueue","title":"计时结束","body":"休息一下"}"""),
        )
        val offer = parse(
            """{"protocol":"quickerlink.mobile-push","version":8,"op":"file.enqueue","transfer":{"id":"$TRANSFER_ID","name":"report.pdf","mime":"application/pdf","size":1024,"sha256":"${"a".repeat(64)}","chunkSize":65536}}""",
        )
        assertTrue(offer is QuickerMobilePush.FileOffer)
        assertEquals("report.pdf", (offer as QuickerMobilePush.FileOffer).descriptor.name)
    }

    @Test
    fun `rejects extra fields old versions and unsafe file metadata`() {
        listOf(
            """{"protocol":"quickerlink.mobile-push","version":7,"op":"text.enqueue","text":"hello"}""",
            """{"protocol":"quickerlink.mobile-push","version":8,"op":"text.enqueue","text":"hello","extra":1}""",
            """{"protocol":"quickerlink.mobile-push","version":8,"op":"notify.enqueue","title":"title","body":""}""",
            """{"protocol":"quickerlink.mobile-push","version":8,"op":"file.enqueue","transfer":{"id":"$TRANSFER_ID","name":"..\\secret.txt","mime":"text/plain","size":1,"sha256":"${"a".repeat(64)}","chunkSize":65536}}""",
            """{"protocol":"quickerlink.mobile-push","version":8,"op":"file.enqueue","transfer":{"id":"$TRANSFER_ID","name":"safe.txt","mime":"text/plain","size":67108865,"sha256":"${"a".repeat(64)}","chunkSize":65536}}""",
        ).forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) { parse(payload) }
        }
    }

    private fun parse(payload: String): QuickerMobilePush =
        QuickerMobilePushProtocol.parse(JsonParser.parseString(payload))

    private companion object {
        const val TRANSFER_ID = "11111111-1111-4111-8111-111111111111"
    }
}
