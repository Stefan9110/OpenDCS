/*
 * Copyright (c) 2026 AtLarge Research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.opendc.web.server.traces

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

private const val MAX_REDIRECTS = 5

private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)

private val HTTP_SCHEMES = setOf("http", "https")

/** Why a fetch stopped, in words for the person who asked for it. */
class FetchFailure(message: String) : IOException(message)

/** Whether this server may fetch from a URL, judged before any request is made. */
sealed interface SourceCheck {
    data object Allowed : SourceCheck

    data class Refused(val reason: String) : SourceCheck
}

/** Whether [uri] is something this server fetches at all: http or https, with a host. */
fun checkShape(uri: URI): SourceCheck =
    when {
        uri.scheme?.lowercase() !in HTTP_SCHEMES -> SourceCheck.Refused("only http and https URLs can be imported")
        uri.host.isNullOrEmpty() -> SourceCheck.Refused("the URL names no host")
        else -> SourceCheck.Allowed
    }

/**
 * Whether [uri], whose host resolved to [addresses], may be fetched. Unless a deployment allows
 * private hosts, every address has to be a public one: a URL is a request this server makes on a
 * user's behalf, and inside a cluster it would otherwise reach whatever the server can.
 */
fun checkSource(
    uri: URI,
    addresses: List<InetAddress>,
    allowPrivateHosts: Boolean,
): SourceCheck {
    val shape = checkShape(uri)
    if (shape is SourceCheck.Refused || allowPrivateHosts) {
        return shape
    }
    val private = addresses.firstOrNull(::isPrivate)
    return if (private == null) SourceCheck.Allowed else SourceCheck.Refused("${uri.host} is not a public address")
}

private fun isPrivate(address: InetAddress): Boolean =
    address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress ||
        (address is Inet6Address && address.address[0].toInt() and 0xfe == 0xfc)

/**
 * Downloads from URLs on a user's behalf: following redirects itself so every hop is checked, and
 * giving up on anything larger than [maxBytes] or still arriving after [deadline].
 */
class RemoteFetch(
    private val allowPrivateHosts: Boolean,
    private val maxBytes: Long,
    private val deadline: Instant,
) {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(CONNECT_TIMEOUT).build()

    fun <T> read(
        uri: URI,
        consume: (InputStream) -> T,
    ): T {
        var current = uri
        repeat(MAX_REDIRECTS + 1) {
            val check = checkSource(current, resolve(current), allowPrivateHosts)
            if (check is SourceCheck.Refused) {
                throw FetchFailure(check.reason)
            }
            val request = HttpRequest.newBuilder(current).timeout(remaining()).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            when (response.statusCode()) {
                in REDIRECTS -> {
                    response.body().close()
                    val location = response.headers().firstValue("location").orElseThrow { FetchFailure("$current redirected nowhere") }
                    current = current.resolve(location)
                }
                HTTP_OK -> {
                    val declared = response.headers().firstValueAsLong("content-length").orElse(0)
                    if (declared > maxBytes) {
                        response.body().close()
                        throw tooLarge()
                    }
                    return CappedStream(response.body()).use(consume)
                }
                else -> {
                    response.body().close()
                    throw FetchFailure("${current.host} answered HTTP ${response.statusCode()}")
                }
            }
        }
        throw FetchFailure("$uri redirected more than $MAX_REDIRECTS times")
    }

    private fun resolve(uri: URI): List<InetAddress> =
        try {
            InetAddress.getAllByName(uri.host).toList()
        } catch (e: IOException) {
            throw FetchFailure("${uri.host} could not be resolved")
        }

    private fun remaining(): Duration {
        val left = Duration.between(Instant.now(), deadline)
        if (left <= Duration.ZERO) {
            throw tookTooLong()
        }
        return left
    }

    private fun tooLarge() = FetchFailure("the file is larger than the ${maxBytes / BYTES_PER_MB} MB this deployment imports")

    private fun tookTooLong() = FetchFailure("the import did not finish before its deadline")

    /** The body, cut off once it grows past the cap or outlasts the deadline. */
    private inner class CappedStream(body: InputStream) : FilterInputStream(body) {
        private var read = 0L

        override fun read(): Int {
            val byte = super.read()
            if (byte >= 0) counted(1)
            return byte
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            val n = super.read(b, off, len)
            if (n > 0) counted(n.toLong())
            return n
        }

        private fun counted(n: Long) {
            read += n
            if (read > maxBytes) throw tooLarge()
            if (Instant.now() > deadline) throw tookTooLong()
        }
    }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val HTTP_OK = 200
        const val BYTES_PER_MB = 1024L * 1024
    }
}
