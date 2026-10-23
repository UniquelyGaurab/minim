package com.minim.music.data.source

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

class OkHttpNewPipeDownloader(private val client: OkHttpClient) : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBody = dataToSend?.toRequestBody(null, 0, dataToSend.size)
            ?: if (httpMethod == "POST") ByteArray(0).toRequestBody(null, 0, 0) else null

        val builder = okhttp3.Request.Builder()
            .url(url)
            .method(httpMethod, requestBody)
            .header("User-Agent", USER_AGENT)

        for ((headerName, headerValueList) in headers) {
            builder.removeHeader(headerName)
            for (headerValue in headerValueList) {
                builder.addHeader(headerName, headerValue)
            }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("Rate limited (HTTP 429)", url)
            }
            val bodyString = response.body?.string().orEmpty()
            val latestUrl = response.request.url.toString()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                bodyString,
                latestUrl
            )
        }
    }

    companion object {
        // v4.9.5: same desktop user agent as NewPipe (Firefox 153 ESR)
        const val USER_AGENT = YoutubeStreamRequest.DESKTOP_USER_AGENT
    }
}
