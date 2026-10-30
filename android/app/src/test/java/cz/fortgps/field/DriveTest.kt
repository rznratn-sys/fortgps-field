package cz.fortgps.field

import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.io.IOException

class DriveTest {
    private val server = MockWebServer()
    private val row = OutboxRow("test:1", "12345678-1234-4234-8234-123456789abc", 1, "{\"synthetic\":true}")
    private fun client() = DriveClient("synthetic-test-token", api = server.url("/drive/").toString(), upload = server.url("/upload/").toString())
    private fun meta() = MockResponse().setBody("{\"id\":\"stable-id\",\"mimeType\":\"application/json\",\"parents\":[\"folder\"],\"trashed\":false}")
    @After fun close() { server.shutdown() }
    @Test fun uploadOnlyConfirmedAfterContentReadback() {
        server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse().setBody("{\"id\":\"stable-id\"}"))
        server.enqueue(meta()); server.enqueue(MockResponse().setBody(row.payload))
        client().putImmutable("stable-id", "folder", row)
        val requests = (1..4).map { server.takeRequest() }
        assertEquals("POST", requests[1].method)
        assertTrue(requests[1].body.readUtf8().contains("\"id\":\"stable-id\""))
        assertTrue(requests.last().path!!.contains("alt=media"))
    }
    @Test fun retryExistingIdNeverCreatesDuplicate() {
        server.enqueue(MockResponse().setBody("{\"id\":\"stable-id\"}")); server.enqueue(meta()); server.enqueue(MockResponse().setBody(row.payload))
        client().putImmutable("stable-id", "folder", row)
        repeat(3) { assertEquals("GET", server.takeRequest().method) }
    }
    @Test fun alreadyCreatedButSearchNotYetVisible409Verified() {
        server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(meta()); server.enqueue(MockResponse().setBody(row.payload))
        client().putImmutable("stable-id", "folder", row)
    }
    @Test(expected = IllegalArgumentException::class) fun mismatchedPayloadNotConfirmed() {
        server.enqueue(MockResponse().setBody("{\"id\":\"stable-id\"}")); server.enqueue(meta()); server.enqueue(MockResponse().setBody("{}"))
        client().putImmutable("stable-id", "folder", row)
    }
    @Test fun expiredAuthorizationReported() {
        server.enqueue(MockResponse().setResponseCode(401))
        try { client().putImmutable("stable-id", "folder", row); fail() } catch (e: DriveFailure) { assertEquals(401, e.code) }
    }
    @Test fun uploadDisconnectNotConfirmedAndRetryUsesSameId() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        try { client().putImmutable("stable-id", "folder", row); fail() } catch (_: IOException) { }
        assertEquals("POST", (1..2).map { server.takeRequest() }.last().method)
        // Simulate server acceptance before connection loss. Same persisted ID is retried.
        server.enqueue(MockResponse().setBody("{\"id\":\"stable-id\"}")); server.enqueue(meta()); server.enqueue(MockResponse().setBody(row.payload))
        client().putImmutable("stable-id", "folder", row)
        repeat(3) { assertEquals("GET", server.takeRequest().method) }
    }
}
