package desu.inugram.helpers

import java.net.HttpURLConnection
import java.net.URL

internal object Http {

    data class Response(val code: Int, val body: String)

    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        connectTimeout: Int,
        readTimeout: Int,
    ): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeout
            conn.readTimeout = readTimeout
            if (body != null) conn.doOutput = true
            for ((name, value) in headers) conn.setRequestProperty(name, value)
            if (body != null) conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            return Response(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
