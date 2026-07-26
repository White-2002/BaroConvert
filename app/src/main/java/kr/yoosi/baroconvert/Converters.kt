package kr.yoosi.baroconvert

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray

private fun decodeBitmap(resolver: ContentResolver, source: Uri): Bitmap =
    resolver.openInputStream(source).use { input ->
        requireNotNull(input) { "선택한 파일을 열 수 없습니다." }
        requireNotNull(BitmapFactory.decodeStream(input)) { "지원하지 않는 이미지입니다." }
    }

object LocalImageToPdfConverter {
    fun convert(resolver: ContentResolver, source: Uri, target: File) {
        val bitmap = decodeBitmap(resolver, source)
        val document = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
            val page = document.startPage(pageInfo)
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            document.finishPage(page)
            target.outputStream().buffered().use(document::writeTo)
        } finally {
            document.close()
            bitmap.recycle()
        }
    }
}

object LocalImageConverter {
    fun convert(resolver: ContentResolver, source: Uri, target: File, extension: String) {
        val bitmap = decodeBitmap(resolver, source)
        try {
            val format = when (extension.lowercase()) {
                "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
                "png" -> Bitmap.CompressFormat.PNG
                "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                else -> error("지원하지 않는 이미지 출력 형식입니다.")
            }
            target.outputStream().buffered().use { output ->
                check(bitmap.compress(format, 92, output)) { "이미지 저장에 실패했습니다." }
            }
        } finally {
            bitmap.recycle()
        }
    }
}

object LocalCopyConverter {
    fun convert(resolver: ContentResolver, source: Uri, target: File) {
        resolver.openInputStream(source).use { input ->
            requireNotNull(input) { "선택한 파일을 열 수 없습니다." }
            target.outputStream().buffered().use(input::copyTo)
        }
    }
}

object ServerConverter {
    fun cloudFormats(baseUrl: String, apiToken: String, inputFormat: String): List<String> {
        require(baseUrl.isNotBlank()) { "변환 서버 주소를 먼저 입력하세요." }
        val safeInput = inputFormat.lowercase().takeIf { it.matches(Regex("[a-z0-9][a-z0-9._+-]{0,31}")) }
            ?: return emptyList()
        val connection = (URL(baseUrl.trimEnd('/') + "/cloud/formats/$safeInput").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            if (apiToken.isNotBlank()) setRequestProperty("X-API-Key", apiToken)
        }
        try {
            if (connection.responseCode !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull().orEmpty()
                error(detail.ifBlank { "CloudConvert 형식 목록 조회 실패 (${connection.responseCode})" })
            }
            val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val outputs: JSONArray = json.optJSONArray("outputs") ?: return emptyList()
            return buildList {
                for (index in 0 until outputs.length()) {
                    outputs.optJSONObject(index)?.optString("format")
                        ?.lowercase()
                        ?.takeIf { it.matches(Regex("[a-z0-9][a-z0-9._+-]{0,31}")) }
                        ?.let(::add)
                }
            }.distinct()
        } finally {
            connection.disconnect()
        }
    }

    fun convert(
        resolver: ContentResolver,
        source: Uri,
        originalName: String,
        sourceMimeType: String,
        target: File,
        targetFormat: String,
        baseUrl: String,
        apiToken: String,
        method: String,
    ) {
        require(baseUrl.isNotBlank()) { "변환 서버 주소를 먼저 입력하세요." }
        val boundary = "BaroConvert-${UUID.randomUUID()}"
        val safeMimeType = sourceMimeType.takeIf {
            it.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+"))
        } ?: "application/octet-stream"
        val methodQuery = URLEncoder.encode(method, Charsets.UTF_8.name())
        val connection = (URL(baseUrl.trimEnd('/') + "/convert/$targetFormat?method=$methodQuery").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (apiToken.isNotBlank()) setRequestProperty("X-API-Key", apiToken)
        }

        try {
            DataOutputStream(BufferedOutputStream(connection.outputStream)).use { output ->
                output.writeBytes("--$boundary\r\n")
                output.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${safeName(originalName)}\"\r\n")
                output.writeBytes("Content-Type: $safeMimeType\r\n\r\n")
                resolver.openInputStream(source).use { input ->
                    requireNotNull(input) { "선택한 파일을 열 수 없습니다." }
                    BufferedInputStream(input).copyTo(output)
                }
                output.writeBytes("\r\n--$boundary--\r\n")
            }

            if (connection.responseCode !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val detail = runCatching { JSONObject(message).optString("detail") }
                    .getOrNull()
                    .orEmpty()
                    .ifBlank { message.take(300) }
                val friendly = when (connection.responseCode) {
                    402 -> detail.ifBlank { "클라우드 무료 사용량을 모두 사용했습니다." }
                    503 -> detail.ifBlank { "선택한 클라우드 변환 서비스가 아직 설정되지 않았습니다." }
                    else -> "서버 변환 실패 (${connection.responseCode}): $detail"
                }
                error(friendly)
            }
            connection.inputStream.use { input -> target.outputStream().buffered().use(input::copyTo) }
        } finally {
            connection.disconnect()
        }
    }

    private fun safeName(name: String): String = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}
