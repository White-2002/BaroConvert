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
import java.util.UUID

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
    fun convert(
        resolver: ContentResolver,
        source: Uri,
        originalName: String,
        sourceMimeType: String,
        target: File,
        targetFormat: String,
        baseUrl: String,
        apiToken: String,
    ) {
        require(baseUrl.isNotBlank()) { "변환 서버 주소를 먼저 입력하세요." }
        val boundary = "BaroConvert-${UUID.randomUUID()}"
        val safeMimeType = sourceMimeType.takeIf {
            it.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+"))
        } ?: "application/octet-stream"
        val connection = (URL(baseUrl.trimEnd('/') + "/convert/$targetFormat").openConnection() as HttpURLConnection).apply {
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
                error("서버 변환 실패 (${connection.responseCode}): ${message.take(300)}")
            }
            connection.inputStream.use { input -> target.outputStream().buffered().use(input::copyTo) }
        } finally {
            connection.disconnect()
        }
    }

    private fun safeName(name: String): String = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
}
