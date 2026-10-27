package com.minim.music.data.ai

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** 5.3.2: App Check debug provider for the built-in Gemini. */
class AppCheckDebugTest {

    private lateinit var server: MockWebServer
    private var clock = 1_000_000L

    private val app = AppCheckApp("123456789012", "1:123456789012:android:abc", "KEY", "com.minim.music.debug", "AB12")
    private val debug = "c554b803-1e2f-4a5b-9c8d-0123456789ab"

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun exchanger() = AppCheckExchanger(OkHttpClient(), server.url("/v1").toString().trimEnd('/'), { clock })

    private fun ok(token: String, ttl: String = "3600s") =
        MockResponse().setResponseCode(200).setBody("""{"token":"$token","ttl":"$ttl"}""")

    @Test
    fun exchangeSendsTheDebugTokenToTheRightPlaceAndCachesTheAnswer() = runBlocking {
        server.enqueue(ok("tok1"))
        val ex = exchanger()
        assertEquals("tok1", ex.token(app, debug))
        val req = server.takeRequest()
        assertEquals("/v1/projects/123456789012/apps/1:123456789012:android:abc:exchangeDebugToken", req.path)
        assertEquals("POST", req.method)
        assertEquals("""{"debug_token":"$debug"}""", req.body.readUtf8())
        assertEquals("KEY", req.getHeader("X-Goog-Api-Key"))
        assertEquals("com.minim.music.debug", req.getHeader("X-Android-Package"))
        assertEquals("AB12", req.getHeader("X-Android-Cert"))
        // the same token again without asking Firebase
        clock += 30 * 60_000L
        assertEquals("tok1", ex.token(app, debug))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aTokenNearItsEndOrARejectedOneIsExchangedAgain() = runBlocking {
        server.enqueue(ok("tok1"))
        server.enqueue(ok("tok2"))
        server.enqueue(ok("tok3"))
        val ex = exchanger()
        assertEquals("tok1", ex.token(app, debug))
        clock += 56 * 60_000L // within five minutes of the hour
        assertEquals("tok2", ex.token(app, debug))
        ex.invalidate()
        assertEquals("tok3", ex.token(app, debug))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun anUnregisteredPhoneIsNotApproved() = runBlocking {
        // exactly what Firebase answered from the build server for a token not on the list
        server.enqueue(MockResponse().setResponseCode(403).setBody(
            """{"error":{"code":403,"message":"App attestation failed.","status":"PERMISSION_DENIED"}}"""
        ))
        try {
            exchanger().token(app, debug)
            fail("should throw")
        } catch (e: GeminiException) {
            assertEquals(GeminiProblem.DeviceNotApproved, e.problem)
            // the debug token never ends up in an error
            assertFalse((e.message ?: "").contains(debug))
        }
    }

    @Test
    fun classifyKeepsKeyAndServiceProblemsApart() {
        val badKey = """{"error":{"code":400,"message":"API key not valid.","status":"INVALID_ARGUMENT",
            "details":[{"reason":"API_KEY_INVALID"}]}}"""
        assertEquals(GeminiProblem.DefaultUnavailable, AppCheckExchanger.classify(400, badKey))
        val off = """{"error":{"code":403,"message":"Firebase App Check API has not been used in project 1 before or it is disabled.",
            "status":"PERMISSION_DENIED","details":[{"reason":"SERVICE_DISABLED"}]}}"""
        assertEquals(GeminiProblem.DefaultUnavailable, AppCheckExchanger.classify(403, off))
        assertEquals(GeminiProblem.Busy, AppCheckExchanger.classify(429, null))
        assertEquals(GeminiProblem.Busy, AppCheckExchanger.classify(503, null))
        assertEquals(GeminiProblem.DeviceNotApproved, AppCheckExchanger.classify(403, null))
    }

    @Test
    fun parseReadsTokenAndLifetime() {
        assertEquals("t" to 3_600_000L, AppCheckExchanger.parse("""{"token":"t","ttl":"3600s"}"""))
        assertEquals("t" to 1_800_500L, AppCheckExchanger.parse("""{"token":"t","ttl":"1800.5s"}"""))
        assertNull(AppCheckExchanger.parse("""{"ttl":"3600s"}"""))
        assertNull(AppCheckExchanger.parse("<html>"))
    }

    @Test
    fun debugTokensLookLikeFirebasesAndAreMaskedForShowing() {
        assertTrue(AppCheckDebugToken.isValid(debug))
        assertFalse(AppCheckDebugToken.isValid("not-a-token"))
        assertEquals("c554b803…89ab", AppCheckDebugToken.masked(debug))
    }

    @Test
    fun geminiRefusingTheAppCheckTokenMeansNotApprovedButOnlyOnTheBuiltInRoute() {
        val invalid = """{"error":{"code":401,"message":"Firebase App Check token is invalid.","status":"UNAUTHENTICATED"}}"""
        assertEquals(GeminiProblem.DeviceNotApproved, GeminiErrors.classify(401, invalid, ownKey = false, model = "m"))
        assertTrue(GeminiErrors.classify(401, invalid, ownKey = true, model = "m") != GeminiProblem.DeviceNotApproved)
    }

    @Test
    fun theBuiltInEndpointCarriesTheAppCheckTokenAndNeverPrintsIt() {
        val e = GeminiEndpoint.firebase("gemini-2.5-flash", "demo-project", "KEY", "1:2:android:3", "pkg", null, appCheckToken = "SECRET")
        assertEquals("SECRET", e.headers["X-Firebase-AppCheck"])
        assertFalse(e.toString().contains("SECRET"))
        val none = GeminiEndpoint.firebase("m", "p", "KEY", null, null, null)
        assertNull(none.headers["X-Firebase-AppCheck"])
    }
}
