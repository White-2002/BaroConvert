package kr.yoosi.baroconvert

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedUris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.streamUri())
            Intent.ACTION_SEND_MULTIPLE -> intent.streamUris()
            else -> emptyList()
        }
        setContent { BaroConvertApp(sharedUris) }
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
    DOCX("DOCX", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    PPTX("PPTX", "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    XLSX("XLSX", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    SVG("SVG", "svg", "image/svg+xml"),
    EPUB("EPUB", "epub", "application/epub+zip"),
    MOBI("MOBI", "mobi", "application/x-mobipocket-ebook"),
    AZW3("AZW3", "azw3", "application/vnd.amazon.ebook"),
    ZIP("ZIP", "zip", "application/zip"),
}

private enum class ConversionMethod(
    val label: String,
    val apiValue: String?,
    val description: String,
) {
    LOCAL("휴대폰 자체", null, "파일이 외부로 전송되지 않고 이 기기에서 바로 변환됩니다."),
    NAS("NAS 무료", "nas", "내 NAS에서 무료로 변환합니다. Office 문서는 글꼴이나 배치가 달라질 수 있습니다."),
    ADOBE("Adobe 고품질", "adobe", "Adobe PDF Services로 전송해 원본 배치와 글꼴을 더 잘 보존합니다."),
    CLOUD("CloudConvert", "cloud", "CloudConvert로 전송해 NAS에서 지원하지 않는 형식을 변환합니다."),
}

private data class PreparedResult(
    val file: File,
    val format: OutputFormat,
    val outputName: String,
)

@Composable
private fun BaroConvertApp(initialUris: List<Uri>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("server", 0) }
    val scope = rememberCoroutineScope()
    val initialFiles = remember(initialUris) {
        initialUris.map { fileInfo(context.contentResolver, it) }
    }
    var selectedFiles by remember { mutableStateOf(initialFiles) }
    val initialFormat = remember(initialFiles) { commonAvailableFormats(initialFiles).firstOrNull() }
    var selectedFormat by remember { mutableStateOf(initialFormat) }
    var selectedMethod by remember { mutableStateOf(recommendedMethod(initialFiles, initialFormat)) }
    var serverUrl by remember { mutableStateOf(prefs.getString("url", "http://192.168.0.10:8787").orEmpty()) }
    var apiToken by remember { mutableStateOf(prefs.getString("token", "").orEmpty()) }
    var status by remember { mutableStateOf("변환할 파일을 선택하세요.") }
    var converting by remember { mutableStateOf(false) }
    var preparedResults by remember { mutableStateOf(emptyList<PreparedResult>()) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var downloadingUpdate by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf("") }
    var serverExpanded by remember { mutableStateOf(false) }

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

    val openFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            preparedResults.forEach { it.file.delete() }
            preparedResults = emptyList()
            val picked = uris.map { fileInfo(context.contentResolver, it) }
            selectedFiles = picked
            selectedFormat = commonAvailableFormats(picked).firstOrNull()
            selectedMethod = recommendedMethod(picked, selectedFormat)
            if (picked.any { it.requiresServer() }) serverExpanded = true
            status = if (selectedFormat == null) {
                "선택한 파일에 공통으로 지원되는 출력 형식이 없습니다."
            } else {
                "모든 파일에 공통으로 가능한 출력 형식을 골라주세요."
            }
        }
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val targetUri = result.data?.data
        val prepared = preparedResults.singleOrNull()
        if (result.resultCode == Activity.RESULT_OK && targetUri != null && prepared != null) {
            runCatching {
                context.contentResolver.openOutputStream(targetUri)?.use { output ->
                    prepared.file.inputStream().use { it.copyTo(output) }
                } ?: error("저장 위치를 열 수 없습니다.")
            }.onSuccess {
                status = "${prepared.format.label} 저장 완료"
                prepared.file.delete()
                preparedResults = emptyList()
            }.onFailure { status = it.message ?: "저장 실패" }
        }
    }

    val saveFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null && preparedResults.isNotEmpty()) {
            runCatching {
                val resolver = context.contentResolver
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
                preparedResults.forEach { prepared ->
                    val targetUri = DocumentsContract.createDocument(
                        resolver,
                        parentUri,
                        prepared.format.mimeType,
                        prepared.outputName,
                    ) ?: error("${prepared.outputName} 파일을 만들 수 없습니다.")
                    resolver.openOutputStream(targetUri)?.use { output ->
                        prepared.file.inputStream().use { it.copyTo(output) }
                    } ?: error("${prepared.outputName} 저장 위치를 열 수 없습니다.")
                }
            }.onSuccess {
                status = "${preparedResults.size}개 파일 저장 완료"
                preparedResults.forEach { it.file.delete() }
                preparedResults = emptyList()
            }.onFailure { status = it.message ?: "일괄 저장 실패" }
        }
    }

    BaroConvertTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "FILE CONVERTER",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "파일 확장자 변환",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "복잡한 설정 없이 3단계로 끝내세요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(6.dp))

                StepCard(
                    number = "1",
                    title = "파일 선택",
                    description = if (selectedFiles.isEmpty()) {
                        "한 개 또는 여러 개의 원본 파일을 골라주세요."
                    } else {
                        "${selectedFiles.size}개 파일이 준비됐습니다."
                    },
                    active = true,
                ) {
                    if (selectedFiles.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                selectedFiles.take(3).forEach { file ->
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (selectedFiles.size > 3) {
                                    Text(
                                        text = "그 외 ${selectedFiles.size - 3}개 파일",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        onClick = { openFiles.launch(arrayOf("*/*")) },
                    ) {
                        Text(if (selectedFiles.isEmpty()) "파일 여러 개 고르기" else "파일 다시 고르기")
                    }
                }

                val formats = commonAvailableFormats(selectedFiles)
                StepCard(
                    number = "2",
                    title = "출력 형식",
                    description = when {
                        selectedFiles.isEmpty() -> "1단계에서 파일을 선택하면 가능한 형식이 나타납니다."
                        formats.isEmpty() -> "선택한 모든 파일에 공통으로 가능한 형식이 없습니다."
                        selectedFiles.size == 1 -> "원하는 확장자 하나를 선택하세요."
                        else -> "${selectedFiles.size}개 파일의 공통 출력 형식만 표시됩니다."
                    },
                    active = selectedFiles.isNotEmpty(),
                ) {
                    if (formats.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            formats.chunked(3).forEach { rowFormats ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rowFormats.forEach { format ->
                                        FilterChip(
                                            selected = selectedFormat == format,
                                            onClick = {
                                                selectedFormat = format
                                                selectedMethod = recommendedMethod(selectedFiles, format)
                                            },
                                            label = { Text(format.label) },
                                            shape = RoundedCornerShape(12.dp),
                                        )
                                    }
                                }
                            }
                        }
                        val methods = commonAvailableMethods(selectedFiles, selectedFormat)
                        if (methods.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "변환 방법",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "원하는 방법을 고르세요. 추천 방법이 자동으로 선택됩니다.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val recommended = recommendedMethod(selectedFiles, selectedFormat)
                            methods.forEach { method ->
                                FilterChip(
                                    selected = selectedMethod == method,
                                    onClick = { selectedMethod = method },
                                    label = {
                                        Text(if (method == recommended) "${method.label} · 추천" else method.label)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                )
                            }
                        }
                    }
                }

                StepCard(
                    number = "3",
                    title = "변환 및 저장",
                    description = when {
                        selectedFiles.isEmpty() -> "파일을 선택하면 변환 버튼이 활성화됩니다."
                        selectedFormat == null -> "변환할 출력 형식을 선택하세요."
                        selectedMethod == null -> "변환 방법을 선택하세요."
                        selectedFiles.size == 1 -> "${selectedFormat!!.label} 파일로 변환할 준비가 됐습니다."
                        else -> "${selectedFiles.size}개 파일을 ${selectedFormat!!.label} 형식으로 변환합니다."
                    },
                    active = selectedFiles.isNotEmpty() && selectedFormat != null && selectedMethod != null,
                ) {
                    selectedMethod?.let { method ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "${method.label} 방식으로 변환 예정",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = method.description,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    if (converting) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                        )
                    }
                    Button(
                        enabled = selectedFiles.isNotEmpty() && selectedFormat != null && selectedMethod != null && !converting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        onClick = {
                            val sources = selectedFiles
                            if (sources.isEmpty()) return@Button
                            val format = selectedFormat ?: return@Button
                            val method = selectedMethod ?: return@Button
                            prefs.edit().putString("url", serverUrl).putString("token", apiToken).apply()
                            converting = true
                            status = "${sources.size}개 파일을 ${format.label}로 변환 중…"
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val results = mutableListOf<PreparedResult>()
                                        try {
                                            sources.forEachIndexed { index, source ->
                                                val output = File.createTempFile(
                                                    "converted-$index-",
                                                    ".${format.extension}",
                                                    context.cacheDir,
                                                )
                                                try {
                                                    convertFile(
                                                        context = context,
                                                        source = source,
                                                        format = format,
                                                        output = output,
                                                        serverUrl = serverUrl,
                                                        apiToken = apiToken,
                                                        method = method,
                                                    )
                                                } catch (error: Throwable) {
                                                    output.delete()
                                                    throw error
                                                }
                                                results += PreparedResult(
                                                    file = output,
                                                    format = format,
                                                    outputName = source.name.substringBeforeLast('.') + ".${format.extension}",
                                                )
                                            }
                                            results
                                        } catch (error: Throwable) {
                                            results.forEach { it.file.delete() }
                                            throw error
                                        }
                                    }
                                }.onSuccess { results ->
                                    preparedResults.forEach { it.file.delete() }
                                    preparedResults = results
                                    status = "변환 완료 — 아래 저장 버튼을 눌러주세요."
                                }.onFailure { status = it.message ?: "변환 실패" }
                                converting = false
                            }
                        }
                    ) {
                        Text(if (converting) "변환 중…" else "변환 시작")
                    }
                    if (preparedResults.isNotEmpty()) {
                        OutlinedButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(18.dp),
                            onClick = {
                                val results = preparedResults
                                if (results.size == 1) {
                                    val prepared = results.single()
                                    saveFile.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = prepared.format.mimeType
                                        putExtra(Intent.EXTRA_TITLE, prepared.outputName)
                                    })
                                } else {
                                    saveFolder.launch(null)
                                }
                            },
                        ) {
                            Text(
                                if (preparedResults.size == 1) "변환된 파일 저장"
                                else "변환된 ${preparedResults.size}개 파일 저장",
                            )
                        }
                        Text(
                            text = "저장 화면을 닫아도 결과가 유지되어 다시 저장할 수 있습니다.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            text = status,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (selectedFiles.any { it.requiresServer() }) {
                        Text(
                            text = "이 형식은 아래의 개인 변환 서버 설정이 필요합니다.",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = "NAS 변환 설정",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "문서·PDF·영상·음원 변환용",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { serverExpanded = !serverExpanded }) {
                                Text(if (serverExpanded) "접기" else "설정")
                            }
                        }
                        if (serverExpanded) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text(
                                text = "NAS 서버를 아직 설치하지 않았다면 이미지와 텍스트 변환부터 사용할 수 있습니다.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedTextField(
                                value = serverUrl,
                                onValueChange = {
                                    serverUrl = it
                                    prefs.edit().putString("url", it).apply()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("서버 주소") },
                                supportingText = { Text("예: http://192.168.0.10:8787") },
                                shape = RoundedCornerShape(16.dp),
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
                                shape = RoundedCornerShape(16.dp),
                                singleLine = true,
                            )
                            Text(
                                text = "외부 네트워크에서는 HTTPS 주소를 사용하세요.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "앱 업데이트",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = updateStatus.ifBlank { "새 버전을 자동으로 확인합니다." },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (checkingUpdate) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        OutlinedButton(
                            shape = RoundedCornerShape(14.dp),
                            onClick = requestUpdateCheck,
                        ) {
                            Text("확인")
                        }
                    }
                }

                Text(
                    text = "파일은 기기에 저장되며, 서버 변환이 필요한 경우에만 설정한 NAS로 전송됩니다.",
                    modifier = Modifier.padding(horizontal = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(12.dp))
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

@Composable
private fun StepCard(
    number: String,
    title: String,
    description: String,
    active: Boolean,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            },
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (active) 2.dp else 0.dp,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = number,
                        color = if (active) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            content()
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
private val officeCloudFormats = listOf(OutputFormat.DOCX, OutputFormat.PPTX, OutputFormat.XLSX)
private val cloudOnlyFormats = mapOf(
    "svg" to listOf(OutputFormat.PDF, OutputFormat.JPG, OutputFormat.PNG),
    "psd" to listOf(OutputFormat.PDF, OutputFormat.JPG, OutputFormat.PNG),
    "ai" to listOf(OutputFormat.PDF, OutputFormat.JPG, OutputFormat.PNG, OutputFormat.SVG),
    "epub" to listOf(OutputFormat.PDF, OutputFormat.MOBI, OutputFormat.AZW3),
    "mobi" to listOf(OutputFormat.PDF, OutputFormat.EPUB, OutputFormat.AZW3),
    "azw3" to listOf(OutputFormat.PDF, OutputFormat.EPUB, OutputFormat.MOBI),
    "rar" to listOf(OutputFormat.ZIP),
    "7z" to listOf(OutputFormat.ZIP),
    "tar" to listOf(OutputFormat.ZIP),
    "gz" to listOf(OutputFormat.ZIP),
)

private fun commonAvailableFormats(files: List<SelectedFile>): List<OutputFormat> {
    if (files.isEmpty()) return emptyList()
    return files.drop(1).fold(availableFormats(files.first())) { common, file ->
        val supported = availableFormats(file)
        common.filter { it in supported }
    }
}

private fun availableFormats(file: SelectedFile): List<OutputFormat> = when {
    file.isImage() -> listOf(OutputFormat.PDF, OutputFormat.JPG, OutputFormat.PNG, OutputFormat.WEBP_IMAGE)
    file.isTextLike() -> listOf(OutputFormat.TXT)
    file.extension() in officeExtensions -> listOf(OutputFormat.PDF)
    file.extension() == "pdf" -> listOf(OutputFormat.JPG_ZIP, OutputFormat.PNG_ZIP) + officeCloudFormats
    file.extension() in audioExtensions -> audioFormats.filterNot { it.extension == file.extension() }
    file.extension() in videoExtensions ->
        (videoFormats.filterNot { it.extension == file.extension() } + audioFormats)
    else -> cloudOnlyFormats[file.extension()].orEmpty()
}

private fun commonAvailableMethods(
    files: List<SelectedFile>,
    format: OutputFormat?,
): List<ConversionMethod> {
    if (files.isEmpty() || format == null) return emptyList()
    return files.drop(1).fold(availableMethods(files.first(), format)) { common, file ->
        val supported = availableMethods(file, format)
        common.filter { it in supported }
    }
}

private fun availableMethods(file: SelectedFile, format: OutputFormat): List<ConversionMethod> = when {
    file.isImage() -> listOf(ConversionMethod.LOCAL)
    file.isTextLike() && format == OutputFormat.TXT -> listOf(ConversionMethod.LOCAL)
    file.extension() in setOf("doc", "docx", "ppt", "pptx", "xls", "xlsx") && format == OutputFormat.PDF ->
        listOf(ConversionMethod.ADOBE, ConversionMethod.NAS, ConversionMethod.CLOUD)
    file.extension() in officeExtensions && format == OutputFormat.PDF -> listOf(ConversionMethod.NAS)
    file.extension() == "pdf" && format in officeCloudFormats ->
        listOf(ConversionMethod.ADOBE, ConversionMethod.CLOUD)
    file.extension() == "pdf" -> listOf(ConversionMethod.NAS)
    file.extension() in audioExtensions || file.extension() in videoExtensions -> listOf(ConversionMethod.NAS)
    format in cloudOnlyFormats[file.extension()].orEmpty() -> listOf(ConversionMethod.CLOUD)
    else -> emptyList()
}

private fun recommendedMethod(
    files: List<SelectedFile>,
    format: OutputFormat?,
): ConversionMethod? = commonAvailableMethods(files, format).firstOrNull()

private fun SelectedFile.isImage(): Boolean =
    extension() !in setOf("svg", "psd", "ai") && (
        mimeType.startsWith("image/") || extension() in
            setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif")
        )

private fun SelectedFile.extension(): String = name.substringAfterLast('.', "").lowercase()

private fun SelectedFile.isTextLike(): Boolean = extension() in textLikeExtensions

private fun SelectedFile.requiresServer(): Boolean =
    !isImage() && !isTextLike() && availableFormats(this).isNotEmpty()

private fun convertFile(
    context: android.content.Context,
    source: SelectedFile,
    format: OutputFormat,
    output: File,
    serverUrl: String,
    apiToken: String,
    method: ConversionMethod,
) {
    when {
        method == ConversionMethod.LOCAL && source.isImage() && format == OutputFormat.PDF ->
            LocalImageToPdfConverter.convert(context.contentResolver, source.uri, output)
        method == ConversionMethod.LOCAL && source.isImage() ->
            LocalImageConverter.convert(context.contentResolver, source.uri, output, format.extension)
        method == ConversionMethod.LOCAL && source.isTextLike() && format == OutputFormat.TXT ->
            LocalCopyConverter.convert(context.contentResolver, source.uri, output)
        method != ConversionMethod.LOCAL ->
            ServerConverter.convert(
                context.contentResolver,
                source.uri,
                source.name,
                source.mimeType,
                output,
                format.serverTarget,
                serverUrl,
                apiToken,
                requireNotNull(method.apiValue),
            )
        else -> error("선택한 변환 조합은 아직 지원하지 않습니다.")
    }
}

private fun Intent.streamUri(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_STREAM)
    }

private fun Intent.streamUris(): List<Uri> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    }

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
