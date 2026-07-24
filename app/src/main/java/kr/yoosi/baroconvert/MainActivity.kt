package kr.yoosi.baroconvert

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedUri = if (intent?.action == Intent.ACTION_SEND) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        } else null
        setContent { BaroConvertApp(sharedUri) }
    }
}

private data class SelectedFile(val uri: Uri, val name: String, val mimeType: String)

private enum class OutputFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
    val serverTarget: String = extension,
) {
    PDF("PDF", "pdf", "application/pdf"),
    TXT("TXT", "txt", "text/plain"),
    JPG("JPG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
    WEBP_IMAGE("WEBP", "webp", "image/webp"),
    JPG_ZIP("JPG 묶음", "zip", "application/zip", "jpg-zip"),
    PNG_ZIP("PNG 묶음", "zip", "application/zip", "png-zip"),
    MP3("MP3", "mp3", "audio/mpeg"),
    M4A("M4A", "m4a", "audio/mp4"),
    WAV("WAV", "wav", "audio/wav"),
    FLAC("FLAC", "flac", "audio/flac"),
    OGG("OGG", "ogg", "audio/ogg"),
    OPUS("OPUS", "opus", "audio/ogg"),
    MP4("MP4", "mp4", "video/mp4"),
    MKV("MKV", "mkv", "video/x-matroska"),
    WEBM("WEBM", "webm", "video/webm"),
}

private data class PreparedResult(val file: File, val format: OutputFormat)

@Composable
private fun BaroConvertApp(initialUri: Uri?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("server", 0) }
    val scope = rememberCoroutineScope()
    val initialFile = remember(initialUri) { initialUri?.let { fileInfo(context.contentResolver, it) } }
    var selected by remember { mutableStateOf(initialFile) }
    var selectedFormat by remember { mutableStateOf(initialFile?.let(::availableFormats)?.firstOrNull()) }
    var serverUrl by remember { mutableStateOf(prefs.getString("url", "http://192.168.0.10:8787").orEmpty()) }
    var apiToken by remember { mutableStateOf(prefs.getString("token", "").orEmpty()) }
    var status by remember { mutableStateOf("파일을 하나 선택하세요.") }
    var converting by remember { mutableStateOf(false) }
    var preparedResult by remember { mutableStateOf<PreparedResult?>(null) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var downloadingUpdate by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf("") }

    val requestUpdateCheck: () -> Unit = {
        checkingUpdate = true
        updateStatus = "GitHub에서 새 버전 확인 중…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AppUpdater.check(context) }
            }.onSuccess { info ->
                updateInfo = info
                updateStatus = if (info == null) "현재 최신 버전입니다." else "새 버전 ${info.versionName}이 있습니다."
            }.onFailure { updateStatus = it.message ?: "업데이트 확인 실패" }
            checkingUpdate = false
        }
    }

    LaunchedEffect(Unit) {
        requestUpdateCheck()
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            preparedResult?.file?.delete()
            preparedResult = null
            val picked = fileInfo(context.contentResolver, uri)
            selected = picked
            selectedFormat = availableFormats(picked).firstOrNull()
            status = if (selectedFormat == null) {
                "이 파일은 아직 지원하는 변환 형식이 없습니다."
            } else {
                "아래에서 원하는 확장자를 선택하세요."
            }
        }
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val targetUri = result.data?.data
        val prepared = preparedResult
        if (result.resultCode == Activity.RESULT_OK && targetUri != null && prepared != null) {
            runCatching {
                context.contentResolver.openOutputStream(targetUri)?.use { output ->
                    prepared.file.inputStream().use { it.copyTo(output) }
                } ?: error("저장 위치를 열 수 없습니다.")
            }.onSuccess {
                status = "${prepared.format.label} 저장 완료"
                prepared.file.delete()
                preparedResult = null
            }.onFailure { status = it.message ?: "저장 실패" }
        }
    }

    BaroConvertTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("바로변환", style = MaterialTheme.typography.headlineMedium)
                Text("파일을 고르면 가능한 출력 확장자만 표시합니다.")

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(selected?.name ?: "선택된 파일 없음", style = MaterialTheme.typography.titleMedium)
                        Text(selected?.mimeType ?: "어떤 파일이든 선택할 수 있습니다.")
                        Button(onClick = { openFile.launch(arrayOf("*/*")) }) { Text("파일 선택") }
                    }
                }

                selected?.let { source ->
                    val formats = availableFormats(source)
                    Text("변환할 확장자", style = MaterialTheme.typography.titleMedium)
                    if (formats.isEmpty()) {
                        Text("이 파일은 아직 지원하지 않습니다.")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            formats.chunked(3).forEach { rowFormats ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rowFormats.forEach { format ->
                                        if (selectedFormat == format) {
                                            Button(onClick = { selectedFormat = format }) { Text(format.label) }
                                        } else {
                                            OutlinedButton(onClick = { selectedFormat = format }) { Text(format.label) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Text("서버 및 업데이트", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = {
                        serverUrl = it
                        prefs.edit().putString("url", it).apply()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("개인 변환 서버 주소") },
                    supportingText = { Text("예: http://192.168.0.10:8787") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiToken,
                    onValueChange = {
                        apiToken = it
                        prefs.edit().putString("token", it).apply()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API 키") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = !checkingUpdate, onClick = requestUpdateCheck) {
                        Text("업데이트 확인")
                    }
                    if (checkingUpdate) CircularProgressIndicator()
                }
                if (updateStatus.isNotBlank()) {
                    Text(updateStatus, style = MaterialTheme.typography.bodySmall)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = selected != null && selectedFormat != null && !converting, onClick = {
                        val source = selected ?: return@Button
                        val format = selectedFormat ?: return@Button
                        prefs.edit().putString("url", serverUrl).putString("token", apiToken).apply()
                        converting = true
                        status = "${format.label}로 변환 중…"
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val output = File.createTempFile("converted-", ".${format.extension}", context.cacheDir)
                                    when {
                                        source.isImage() && format == OutputFormat.PDF ->
                                            LocalImageToPdfConverter.convert(context.contentResolver, source.uri, output)
                                        source.isImage() ->
                                            LocalImageConverter.convert(context.contentResolver, source.uri, output, format.extension)
                                        source.isTextLike() && format == OutputFormat.TXT ->
                                            LocalCopyConverter.convert(context.contentResolver, source.uri, output)
                                        source.requiresServer() ->
                                            ServerConverter.convert(
                                                context.contentResolver,
                                                source.uri,
                                                source.name,
                                                source.mimeType,
                                                output,
                                                format.serverTarget,
                                                serverUrl,
                                                apiToken,
                                            )
                                        else -> error("선택한 변환 조합은 아직 지원하지 않습니다.")
                                    }
                                    output
                                }
                            }.onSuccess { output ->
                                preparedResult?.file?.delete()
                                preparedResult = PreparedResult(output, format)
                                status = "변환 완료 — 저장 위치를 선택하세요."
                                val outputName = source.name.substringBeforeLast('.') + ".${format.extension}"
                                saveFile.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = format.mimeType
                                    putExtra(Intent.EXTRA_TITLE, outputName)
                                })
                            }.onFailure { status = it.message ?: "변환 실패" }
                            converting = false
                        }
                    }) { Text("변환하기") }
                    if (converting) CircularProgressIndicator()
                }

                Text(status, color = MaterialTheme.colorScheme.primary)
                if (selected?.requiresServer() == true) {
                    Text(
                        "문서·PDF·영상·음원은 설정한 개인 서버로 전송됩니다. 외부 네트워크에서는 HTTPS를 사용하세요.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        updateInfo?.let { info ->
            AlertDialog(
            onDismissRequest = { if (!downloadingUpdate) updateInfo = null },
            title = { Text("새 버전 ${info.versionName}") },
            text = { Text("업데이트 파일을 받아 설치 화면을 열까요? 약 ${info.size / 1024 / 1024}MB입니다.") },
            confirmButton = {
                TextButton(enabled = !downloadingUpdate, onClick = {
                    downloadingUpdate = true
                    updateStatus = "업데이트 다운로드 중…"
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                AppUpdater.download(context, info)
                            }
                        }.onSuccess { apk ->
                            if (AppUpdater.launchInstaller(context, apk)) {
                                updateStatus = "설치 화면을 열었습니다."
                            } else {
                                updateStatus = "이 앱의 설치 권한을 허용한 뒤 업데이트를 다시 누르세요."
                            }
                        }.onFailure { updateStatus = it.message ?: "업데이트 다운로드 실패" }
                        downloadingUpdate = false
                    }
                }) { Text(if (downloadingUpdate) "다운로드 중…" else "업데이트") }
            },
            dismissButton = {
                TextButton(enabled = !downloadingUpdate, onClick = { updateInfo = null }) { Text("나중에") }
            },
            )
        }
    }
}

private val officeExtensions = setOf(
    "doc", "docx", "odt", "rtf", "txt", "html",
    "ppt", "pptx", "odp", "xls", "xlsx", "ods", "csv",
)
private val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma")
private val videoExtensions = setOf("mp4", "mov", "mkv", "avi", "webm", "m4v", "3gp", "wmv", "flv")
private val textLikeExtensions = setOf("json", "xml", "yaml", "yml", "md", "markdown", "log", "ini", "conf")
private val audioFormats = listOf(
    OutputFormat.MP3, OutputFormat.M4A, OutputFormat.WAV,
    OutputFormat.FLAC, OutputFormat.OGG, OutputFormat.OPUS,
)
private val videoFormats = listOf(OutputFormat.MP4, OutputFormat.MKV, OutputFormat.WEBM)

private fun availableFormats(file: SelectedFile): List<OutputFormat> = when {
    file.isImage() -> listOf(OutputFormat.PDF, OutputFormat.JPG, OutputFormat.PNG, OutputFormat.WEBP_IMAGE)
    file.isTextLike() -> listOf(OutputFormat.TXT)
    file.extension() in officeExtensions -> listOf(OutputFormat.PDF)
    file.extension() == "pdf" -> listOf(OutputFormat.JPG_ZIP, OutputFormat.PNG_ZIP)
    file.extension() in audioExtensions -> audioFormats.filterNot { it.extension == file.extension() }
    file.extension() in videoExtensions ->
        (videoFormats.filterNot { it.extension == file.extension() } + audioFormats)
    else -> emptyList()
}

private fun SelectedFile.isImage(): Boolean =
    mimeType.startsWith("image/") || name.substringAfterLast('.', "").lowercase() in
        setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif")

private fun SelectedFile.extension(): String = name.substringAfterLast('.', "").lowercase()

private fun SelectedFile.isTextLike(): Boolean = extension() in textLikeExtensions

private fun SelectedFile.requiresServer(): Boolean =
    !isImage() && !isTextLike() && availableFormats(this).isNotEmpty()

private fun fileInfo(resolver: android.content.ContentResolver, uri: Uri): SelectedFile {
    var name = "selected-file"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) name = cursor.getString(0)
    }
    val detectedType = resolver.getType(uri).orEmpty().ifBlank {
        when {
            name.endsWith(".pptx", ignoreCase = true) ->
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/octet-stream"
        }
    }
    return SelectedFile(uri, name, detectedType)
}
