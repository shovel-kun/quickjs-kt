package quickjs.sample.openai.bindings

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.binding.toJsObject
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.http.HttpMethod
import io.ktor.util.toMap
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.read
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import quickjs_kt.samples.openai.generated.resources.Res
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalResourceApi::class)
internal suspend fun QuickJs.defineFetch(coroutineScope: CoroutineScope): Cleanup {
    val client = HttpClient(CIO)

    val bodyChannelId = AtomicLong(0L)
    val streamingJobs = ConcurrentHashMap<Long, Job>()
    val responseBodyChannels = ConcurrentHashMap<Long, Channel<ByteArray?>>()

    val cleanup: Cleanup = {
        coroutineScope.cancel()
        for ((_, channel) in responseBodyChannels) {
            channel.close()
        }
        client.close()
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    function("_decodeTextUtf8") { args ->
        val bytes = when (val first = args.first()) {
            is ByteArray -> first
            is UByteArray -> first.asByteArray()
            else -> error("_decodeTextUtf8() requires a ByteArray or UByteArray")
        }
        bytes.decodeToString()
    }

    @Suppress("UNCHECKED_CAST")
    asyncFunction("_fetchInternal") { args ->
        val url = args.first() as String
        val init = if (args.size > 1) args[1] as Map<String, Any?>? else null
        val request = requestFromInit(init)
        val statement = client.prepareRequest {
            url(url)
            method = HttpMethod.Post
            for (header in request.headers) {
                header(header.key, header.value)
            }
            if (request.body != null) {
                setBody(request.body)
            }
        }

        val responseDeferred = CompletableDeferred<HttpResponse>()
        val channelDeferred = CompletableDeferred<Channel<ByteArray?>>()
        val job = coroutineScope.launch {
            statement.execute { res ->
                responseDeferred.complete(res)
                val channel = res.body<ByteReadChannel>()
                val externalChannel = Channel<ByteArray?>(capacity = 100)
                channelDeferred.complete(externalChannel)
                readStreamBody(res, channel, externalChannel)
            }
        }

        val responseId = bodyChannelId.getAndIncrement()

        streamingJobs[responseId] = job
        responseBodyChannels[responseId] = channelDeferred.await()

        val response = responseDeferred.await()

        mapOf(
            "url" to response.request.url.toString(),
            "status" to response.status.value,
            "statusText" to response.status.description,
            "ok" to (response.status.value in (200..299)),
            "bodyChannelId" to responseId,
            "bodyUsed" to false,
            "headers" to response.headers.toMap().toJsObject(),
        ).toJsObject()
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    asyncFunction("_readFromResponseChannel") { args ->
        val id = args.first() as Long
        val channel = responseBodyChannels[id] ?: error("Body channel $id not found.")

        val bytes = channel.receive()?.toUByteArray()
        if (bytes != null) {
            bytes
        } else {
            responseBodyChannels.remove(id)
            null
        }
    }

    evaluate<Any?>(
        code = Res.readBytes("files/web-fetch.js").decodeToString(),
        filename = "web-fetch.js",
    )

    return cleanup
}

private suspend fun readStreamBody(
    response: HttpResponse,
    channel: ByteReadChannel,
    externalChannel: Channel<ByteArray?>,
) {
    val contentType = response.headers["Content-Type"]
    if (contentType == "text/event-stream") {
        val delimiter = "\n\n".toByteArray()
        val buffer = ByteArrayOutputStream()
        val tempBuffer = ByteArray(4096)

        while (true) {
            val readCount = channel.readAvailable(tempBuffer)
            if (readCount == -1) {
                externalChannel.send(null)
                break
            }

            buffer.write(tempBuffer, 0, readCount)
            val bytes = buffer.toByteArray()

            // Check if we have the delimiter in the buffer
            val delimiterIndex = bytes.indexOf(delimiter)
            if (delimiterIndex != -1) {
                // Send the data up to and including the delimiter
                val message = bytes.copyOf(delimiterIndex + delimiter.size)
                externalChannel.send(message)

                // Keep any remaining data after the delimiter
                buffer.reset()
                if (delimiterIndex + delimiter.size < bytes.size) {
                    buffer.write(bytes, delimiterIndex + delimiter.size, bytes.size - (delimiterIndex + delimiter.size))
                }
            }
        }
    } else {
        while (true) {
            val readCount = channel.read { source, start, endExclusive ->
                val count = endExclusive - start
                if (count == 0) {
                    externalChannel.send(null)
                } else {
                    val bytes = source.sliceArray(start..endExclusive)
                    externalChannel.send(bytes)
                }
                count
            }
            if (readCount <= 0) {
                break
            }
        }
    }
    externalChannel.close()
}

private data class Request(
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

@Suppress("UNCHECKED_CAST")
private fun requestFromInit(init: Map<String, Any?>?): Request {
    init ?: return Request()
    val method = init["method"] as String? ?: "GET"
    val headers = init["headers"] as Map<String, String>? ?: emptyMap()
    val body = init["body"] as String?
    return Request(
        method = method,
        headers = headers,
        body = body,
    )
}

private fun ByteArray.indexOf(subArray: ByteArray): Int {
    if (subArray.isEmpty()) return -1
    outer@ for (i in 0..this.size - subArray.size) {
        for (j in subArray.indices) {
            if (this[i + j] != subArray[j]) continue@outer
        }
        return i
    }
    return -1
}

