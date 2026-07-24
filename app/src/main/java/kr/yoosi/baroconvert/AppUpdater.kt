package kr.yoosi.baroconvert

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val size: Long,
    val sha256: String,
    val downloadUrl: String,
)

object AppUpdater {
    private const val MANIFEST_URL =
        "https://github.com/White-2002/BaroConvert/releases/latest/download/update.json"

    fun check(context: Context): UpdateInfo? {
        val connection = open(MANIFEST_URL)
        try {
            if (connection.responseCode !in 200..299) error(responseError(connection))
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val info = UpdateInfo(
                versionCode = json.getLong("versionCode"),
                versionName = json.getString("versionName"),
                size = json.getLong("size"),
                sha256 = json.getString("sha256"),
                downloadUrl = json.getString("downloadUrl"),
            )
            return info.takeIf { it.versionCode > installedVersionCode(context) }
        } finally {
            connection.disconnect()
        }
    }

    fun download(context: Context, info: UpdateInfo): File {
        val connection = open(info.downloadUrl).apply {
            readTimeout = 300_000
        }
        val directory = context.externalCacheDir ?: context.cacheDir
        val target = File(directory, "baroconvert-update-${info.versionCode}.apk")
        try {
            if (connection.responseCode !in 200..299) error(responseError(connection))
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            check(actualHash.equals(info.sha256, ignoreCase = true)) { "업데이트 파일 검증에 실패했습니다." }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun launchInstaller(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BaroConvert-Android")
            setRequestProperty("Accept", "application/octet-stream, application/json")
        }

    private fun responseError(connection: HttpURLConnection): String {
        val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return "업데이트 서버 오류 (${connection.responseCode}): ${detail.take(200)}"
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }
}
