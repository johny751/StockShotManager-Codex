package com.stockshot.manager.ui.screen

import android.Manifest
import android.widget.Toast
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.stockshot.manager.BuildConfig
import com.stockshot.manager.data.SettingsStore
import com.stockshot.manager.ui.SystemIntents
import com.stockshot.manager.ui.vm.SettingsViewModel
import com.stockshot.manager.util.QrShareUtils
import androidx.core.content.ContextCompat
import com.stockshot.manager.util.StorageUtils
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController, vm: SettingsViewModel = viewModel()) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showVersionHistory by remember { mutableStateOf(false) }
    var majorityPercentText by remember(state.capitalMajorityThreshold) {
        mutableStateOf((state.capitalMajorityThreshold * 100f).toString().removeSuffix(".0"))
    }
    val majorityPercent = majorityPercentText.toFloatOrNull()
    val majorityPercentValid = majorityPercent != null && majorityPercent.isFinite() &&
        majorityPercent > 50f && majorityPercent <= 100f
    val usingMajorityRule = state.capitalMethod == SettingsStore.CAPITAL_METHOD_POINTWISE &&
        state.capitalRule == SettingsStore.CAPITAL_RULE_MAJORITY

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val path = StorageUtils.treeUriToPath(uri)
            if (path != null) {
                vm.update { it.copy(storageRoot = path) }
                vm.save()
            } else {
                Toast.makeText(
                    context,
                    "\u4ec5\u652f\u6301\u4e3b\u5b58\u50a8\u76ee\u5f55\uff0c\u8bf7\u91cd\u65b0\u9009\u62e9",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // \u5907\u4efd\uff1a\u521b\u5efa JSON \u6587\u4ef6\u7531\u7528\u6237\u9009\u4f4d\u7f6e
    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) vm.backupTo(uri)
    }

    // \u6062\u590d\uff1a\u9009\u62e9\u5df2\u6709\u5907\u4efd\u6587\u4ef6
    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.restoreFrom(uri)
    }

    // QR scan launcher (embedded camera) for one-shot settings import.
    val qrScanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result?.contents
        if (contents != null) vm.importFromScan(contents)
    }
    fun launchQrScan() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .setPrompt("")
        qrScanLauncher.launch(options)
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchQrScan()
        } else {
            Toast.makeText(
                context,
                "\u9700\u8981\u76f8\u673a\u6743\u9650\u624d\u80fd\u626b\u7801\u5bfc\u5165\u8bbe\u7f6e",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            Toast.makeText(context, "\u5df2\u4fdd\u5b58", Toast.LENGTH_SHORT).show()
            vm.consumeSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "\u8fd4\u56de")
                    }
                },
                title = { Text("\u8bbe\u7f6e") }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DiagnosticsLogSettings()

            // \u884c\u60c5\u6570\u636e\u6e90\uff1a\u4e09\u9009\u4e00 + \u8fde\u63a5\u6d4b\u8bd5
            SectionTitle("\u884c\u60c5\u6570\u636e\u6e90")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.quoteSourceType == SettingsStore.QUOTE_SOURCE_SINA,
                    onClick = { vm.update { it.copy(quoteSourceType = SettingsStore.QUOTE_SOURCE_SINA) } },
                    label = { Text("\u65b0\u6d6a\u884c\u60c5") }
                )
                FilterChip(
                    selected = state.quoteSourceType == SettingsStore.QUOTE_SOURCE_PROVIDER,
                    onClick = { vm.update { it.copy(quoteSourceType = SettingsStore.QUOTE_SOURCE_PROVIDER) } },
                    label = { Text("\u63a5\u53e3\u83b7\u53d6") }
                )
                FilterChip(
                    selected = state.quoteSourceType == SettingsStore.QUOTE_SOURCE_LAN,
                    onClick = { vm.update { it.copy(quoteSourceType = SettingsStore.QUOTE_SOURCE_LAN) } },
                    label = { Text("\u5c40\u57df\u7f51") }
                )
            }
            Text(
                text = when (state.quoteSourceType) {
                    SettingsStore.QUOTE_SOURCE_PROVIDER ->
                        "\u4ece\u540c\u8bbe\u5907\u7684\u884c\u60c5\u63d0\u4f9b\u65b9 APP \u8bfb\u53d6\uff08content://com.floatstocktip.provider\uff09"
                    SettingsStore.QUOTE_SOURCE_LAN ->
                        "\u4ece\u5c40\u57df\u7f51\u670d\u52a1\u7aef\u8bfb\u53d6\uff08http://<IP>:8080/stock/{code}\uff09"
                    else ->
                        "\u9ed8\u8ba4\u65b0\u6d6a\u884c\u60c5\uff0c\u65e0\u9700\u4efb\u4f55\u914d\u7f6e\u5373\u53ef\u4f7f\u7528"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.quoteSourceType == SettingsStore.QUOTE_SOURCE_LAN) {
                OutlinedTextField(
                    value = state.lanApiIp,
                    onValueChange = { v -> vm.update { it.copy(lanApiIp = v) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("\u5c40\u57df\u7f51\u670d\u52a1\u7aef IP") },
                    placeholder = { Text("\u4f8b\u5982 192.168.1.100") }
                )
            }

            // \u8fde\u63a5\u6d4b\u8bd5
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.testCode,
                    onValueChange = { v ->
                        val n = v.filter { c -> c.isDigit() }.take(6)
                        vm.update { it.copy(testCode = n) }
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("\u6d4b\u8bd5\u80a1\u7968\u4ee3\u7801") }
                )
                Button(
                    onClick = { vm.runQuoteSourceTest() },
                    enabled = !state.testing
                ) {
                    if (state.testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("\u6d4b\u8bd5\u8fde\u63a5")
                    }
                }
            }
            state.testResult?.let { result ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (state.testError)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = result,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        color = if (state.testError)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionTitle("\u81ea\u52a8\u5316\u622a\u56fe")
            OutlinedTextField(
                value = state.targetAppPackage,
                onValueChange = { v -> vm.update { it.copy(targetAppPackage = v) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("\u76ee\u6807\u5e94\u7528\u5305\u540d (target_app_package)") }
            )
            OutlinedTextField(
                value = state.codeViewId,
                onValueChange = { v -> vm.update { it.copy(codeViewId = v) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("\u4ee3\u7801\u63a7\u4ef6 ID (code_view_id)") }
            )
            OutlinedTextField(
                value = state.nextButtonId,
                onValueChange = { v -> vm.update { it.copy(nextButtonId = v) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("\u4e0b\u4e00\u53ea\u6309\u94ae ID (next_button_id)") }
            )
            OutlinedTextField(
                value = if (state.screenshotDelayMs == 0) "" else state.screenshotDelayMs.toString(),
                onValueChange = { v ->
                    val n = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0
                    vm.update { it.copy(screenshotDelayMs = n) }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text("\u622a\u56fe\u5ef6\u65f6 (\u6beb\u79d2)") }
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("\u542f\u7528 OCR \u8bc6\u522b\u4ee3\u7801")
                Switch(
                    checked = state.ocrEnabled,
                    onCheckedChange = { v -> vm.update { it.copy(ocrEnabled = v) } }
                )
            }

            SectionTitle("\u81ea\u52a8\u8bc6\u522b\u6807\u8bb0")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("\u542f\u7528\u81ea\u52a8\u8bc6\u522b\uff08\u7ea2\u7eff\u70b9\u91cd\u5408\uff09")
                Switch(
                    checked = state.autoDetectEnabled,
                    onCheckedChange = { v -> vm.update { it.copy(autoDetectEnabled = v) } }
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("\u547d\u4e2d\u540e\u6570\u636e\u5e93\u81ea\u52a8\u6807\u8bb0")
                Switch(
                    checked = state.autoMarkEnabled,
                    onCheckedChange = { v -> vm.update { it.copy(autoMarkEnabled = v) } }
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("\u547d\u4e2d\u540e\u957f\u6309\u8bfb\u53d6\u4e70\u5165\u4ef7")
                Switch(
                    checked = state.autoBuyPriceEnabled,
                    onCheckedChange = { v -> vm.update { it.copy(autoBuyPriceEnabled = v) } }
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("\u8981\u6c42\u7d2b\u3001\u9ec4\u4e24\u6761\u90fd\u5728\u7eff\u7ebf\u4e0a")
                Switch(
                    checked = state.capitalRequireYellow,
                    onCheckedChange = { v -> vm.update { it.copy(capitalRequireYellow = v) } }
                )
            }
            Text("资金线判断方式", fontWeight = FontWeight.Medium)
            FilterChip(
                selected = state.capitalMethod == SettingsStore.CAPITAL_METHOD_MEDIAN,
                onClick = { vm.update { it.copy(capitalMethod = SettingsStore.CAPITAL_METHOD_MEDIAN) } },
                label = { Text("方案一：中位位置（原方法）") },
                modifier = Modifier.fillMaxWidth(),
            )
            FilterChip(
                selected = state.capitalMethod == SettingsStore.CAPITAL_METHOD_POINTWISE,
                onClick = { vm.update { it.copy(capitalMethod = SettingsStore.CAPITAL_METHOD_POINTWISE) } },
                label = { Text("方案二：逐列比较") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = if (state.capitalMethod == SettingsStore.CAPITAL_METHOD_MEDIAN) {
                    "比较窗口内各条线的中位位置，不代表每个时刻都满足上下关系。"
                } else {
                    "在相同时间列比较资金线，按下方规则判断。"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.capitalMethod == SettingsStore.CAPITAL_METHOD_POINTWISE) {
                listOf(
                    SettingsStore.CAPITAL_RULE_ALL to "整段每列满足",
                    SettingsStore.CAPITAL_RULE_MAJORITY to "大部分时刻满足",
                    SettingsStore.CAPITAL_RULE_SIGNAL to "信号出现时满足",
                ).forEach { (rule, label) ->
                    FilterChip(
                        selected = state.capitalRule == rule,
                        onClick = { vm.update { it.copy(capitalRule = rule) } },
                        label = { Text(label) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = when (state.capitalRule) {
                        SettingsStore.CAPITAL_RULE_MAJORITY ->
                            "窗口必须完整可读，再按整段总列数计算满足比例；任何必需线缺失则无法判断。"
                        SettingsStore.CAPITAL_RULE_SIGNAL ->
                            "只检查红绿信号点对应的时间列；缺失、无法判断时不算满足。"
                        else -> "窗口内每一列都须满足；缺失、无法判断的列不能跳过。"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (usingMajorityRule) {
                    OutlinedTextField(
                        value = majorityPercentText,
                        onValueChange = { text ->
                            majorityPercentText = text
                            val percent = text.toFloatOrNull()
                            if (percent != null && percent.isFinite() && percent > 50f && percent <= 100f) {
                                vm.update { it.copy(capitalMajorityThreshold = percent / 100f) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = !majorityPercentValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text("满足比例（%，大于 50 且不超过 100）") },
                        supportingText = {
                            Text(if (majorityPercentValid) "默认 90%。" else "请输入大于 50 且不超过 100 的百分比。")
                        },
                    )
                }
            }
            Text("信号选择", fontWeight = FontWeight.Medium)
            FilterChip(
                selected = state.signalSelection == SettingsStore.SIGNAL_SELECTION_LATEST,
                onClick = { vm.update { it.copy(signalSelection = SettingsStore.SIGNAL_SELECTION_LATEST) } },
                label = { Text("最新信号（最右一对）") },
                modifier = Modifier.fillMaxWidth(),
            )
            FilterChip(
                selected = state.signalSelection == SettingsStore.SIGNAL_SELECTION_ANY,
                onClick = { vm.update { it.copy(signalSelection = SettingsStore.SIGNAL_SELECTION_ANY) } },
                label = { Text("任意历史信号（从左到右）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = if (state.signalSelection == SettingsStore.SIGNAL_SELECTION_ANY) {
                    "从左到右逐对检查历史红绿信号，首个同时符合资金条件和规则分数门槛即停止。"
                } else {
                    "只检查最右侧一对红绿信号；该对不符合时，不回退到更早信号。"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("修改后请点击底部“保存”，重新启动采集后应用新设置。", fontSize = 12.sp)
            Text(
                text = "\u9608\u503c\u5fae\u8c03\uff08\u73b0\u573a\u8bc6\u522b\u4e0d\u51c6\u65f6\u518d\u6539\uff0c\u4e00\u822c\u4fdd\u6301\u9ed8\u8ba4\uff09\uff1a\u70b9\u5bf9\u9f50\u5bb9\u5dee 1.0\uff08\u8c03\u5927\u66f4\u5bbd\u677e\uff0c0.3-3.0\uff09\uff1b\u6700\u5c0f\u7f6e\u4fe1\u5ea6 0 \u8868\u793a\u4e0d\u9650\uff080-1\uff0c\u8c03\u9ad8\u53ef\u8fc7\u6ee4\u4f4e\u628a\u63e1\u547d\u4e2d\uff09\u3002",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.dotToleranceScale.toString(),
                    onValueChange = { v -> vm.update { it.copy(dotToleranceScale = v.toFloatOrNull() ?: it.dotToleranceScale) } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text("\u70b9\u5bf9\u9f50\u5bb9\u5dee (0.3-3.0)") }
                )
                OutlinedTextField(
                    value = state.minConfidence.toString(),
                    onValueChange = { v -> vm.update { it.copy(minConfidence = v.toFloatOrNull() ?: it.minConfidence) } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text("\u6700\u5c0f\u7f6e\u4fe1\u5ea6 (0-1)") }
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.capitalWindowScale.toString(),
                    onValueChange = { v -> vm.update { it.copy(capitalWindowScale = v.toFloatOrNull() ?: it.capitalWindowScale) } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text("\u8d44\u91d1\u7ebf\u5de6\u4fa7\u7a97\u53e3 (0.1-1.0)") }
                )
            }

            SectionTitle("\u4e70\u5165\u4ef7\u63a7\u4ef6\u8bfb\u53d6\uff08\u65e0\u969c\u788d\uff09")
            Text(
                text = "\u67e5\u627e\u533a\u57df\u4e3a\u5f52\u4e00\u5316 0-1\uff0c\u53ef\u7528\u6d4b\u8bd5\u8bfb\u53d6\u56de\u663e\u547d\u4e2d\u6587\u672c\u4e0e\u5019\u9009\u6570\u3002",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.nodeRegionLeft.toString(),
                    onValueChange = { v -> vm.update { it.copy(nodeRegionLeft = v.toFloatOrNull() ?: it.nodeRegionLeft) } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text("\u533a\u57df left") }
                )
                OutlinedTextField(
                    value = state.nodeRegionTop.toString(),
                    onValueChange = { v -> vm.update { it.copy(nodeRegionTop = v.toFloatOrNull() ?: it.nodeRegionTop) } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text("\u533a\u57df top") }
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.nodeRegionRight.toString(),
                    onValueChange = { v -> vm.update { it.copy(nodeRegionRight = v.toFloatOrNull() ?: it.nodeRegionRight) } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text("\u533a\u57df right") }
                )
                OutlinedTextField(
                    value = state.nodeRegionBottom.toString(),
                    onValueChange = { v -> vm.update { it.copy(nodeRegionBottom = v.toFloatOrNull() ?: it.nodeRegionBottom) } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text("\u533a\u57df bottom") }
                )
            }
            OutlinedTextField(
                value = state.nodeClass,
                onValueChange = { v -> vm.update { it.copy(nodeClass = v) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("\u63a7\u4ef6 class \u540d\uff08\u7cbe\u786e\u5339\u914d\uff0c\u53ef\u7a7a\uff09") }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = if (state.nodeWidth == 0) "" else state.nodeWidth.toString(),
                    onValueChange = { v -> vm.update { it.copy(nodeWidth = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("\u63a7\u4ef6\u5bbd (px, 0=\u4e0d\u9650)") }
                )
                OutlinedTextField(
                    value = if (state.nodeHeight == 0) "" else state.nodeHeight.toString(),
                    onValueChange = { v -> vm.update { it.copy(nodeHeight = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("\u63a7\u4ef6\u9ad8 (px, 0=\u4e0d\u9650)") }
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.nodeSizeTol.toString(),
                    onValueChange = { v -> vm.update { it.copy(nodeSizeTol = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("\u5bbd\u9ad8\u50cf\u7d20\u5bb9\u5dee") }
                )
                OutlinedTextField(
                    value = state.nodeIndex.toString(),
                    onValueChange = { v -> vm.update { it.copy(nodeIndex = (v.filter { c -> c.isDigit() }.toIntOrNull() ?: 1).coerceAtLeast(1)) } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("\u7b2c\u51e0\u4e2a (\u4ece1\u5f00\u59cb)") }
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.nodeReadDelayMs.toString(),
                    onValueChange = { v -> vm.update { it.copy(nodeReadDelayMs = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("\u8bfb\u53d6\u5ef6\u8fdf (ms)") }
                )
                OutlinedTextField(
                    value = state.nodeLongpressMs.toString(),
                    onValueChange = { v -> vm.update { it.copy(nodeLongpressMs = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("\u957f\u6309\u65f6\u957f (ms)") }
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.postReadDelayMs.toString(),
                    onValueChange = { v -> vm.update { it.copy(postReadDelayMs = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("\u8bfb\u4ef7\u540e\u5ef6\u8fdf (ms)") }
                )
                Spacer(Modifier.weight(1f))
            }
            Text(
                text = "\u5173\u95ed\u5341\u5b57\u5149\u6807\u65b9\u5f0f\uff1a\u547d\u4e2d\u8bfb\u4ef7\u540e\u6536\u8d77\u62a5\u4ef7\u6d6e\u5c42\uff0c\u907f\u514d\u8bef\u70b9\u6807\u9898\u680f\u3002",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.closeCrosshairMode == SettingsStore.CLOSE_CROSSHAIR_BACK,
                    onClick = { vm.update { it.copy(closeCrosshairMode = SettingsStore.CLOSE_CROSSHAIR_BACK) } },
                    label = { Text("\u8fd4\u56de\u952e") },
                )
                FilterChip(
                    selected = state.closeCrosshairMode == SettingsStore.CLOSE_CROSSHAIR_TAP_CHART,
                    onClick = { vm.update { it.copy(closeCrosshairMode = SettingsStore.CLOSE_CROSSHAIR_TAP_CHART) } },
                    label = { Text("\u70b9\u56fe\u8868\u4e2d\u592e") },
                )
                FilterChip(
                    selected = state.closeCrosshairMode == SettingsStore.CLOSE_CROSSHAIR_NONE,
                    onClick = { vm.update { it.copy(closeCrosshairMode = SettingsStore.CLOSE_CROSSHAIR_NONE) } },
                    label = { Text("\u4e0d\u5904\u7406") },
                )
            }
            Button(
                onClick = { vm.runNodeReadTest() },
                enabled = !state.nodeTesting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.nodeTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("\u6d4b\u8bd5\u8bfb\u53d6")
                }
            }
            state.nodeTestResult?.let { result ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (state.nodeTestError)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = result,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        color = if (state.nodeTestError)
                            MaterialTheme.colorScheme.onErrorContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionTitle("\u56fe\u7247\u67e5\u770b\u5668")
            Text(
                text = "\u9009\u62e9\u7ffb\u9875\u624b\u52bf\u65b9\u5411\uff0c\u653e\u5927\u56fe\u7247\u540e\u7684\u62d6\u52a8\u4e0d\u53d7\u5f71\u54cd\u3002",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.viewerPageDirection == SettingsStore.VIEWER_PAGE_HORIZONTAL,
                    onClick = {
                        vm.update {
                            it.copy(viewerPageDirection = SettingsStore.VIEWER_PAGE_HORIZONTAL)
                        }
                    },
                    label = { Text("\u5de6\u53f3\u6ed1\u52a8") },
                )
                FilterChip(
                    selected = state.viewerPageDirection == SettingsStore.VIEWER_PAGE_VERTICAL,
                    onClick = {
                        vm.update {
                            it.copy(viewerPageDirection = SettingsStore.VIEWER_PAGE_VERTICAL)
                        }
                    },
                    label = { Text("\u4e0a\u4e0b\u6ed1\u52a8") },
                )
            }
            SectionTitle("\u6743\u9650")
            OutlinedButton(
                onClick = {
                    context.startActivity(SystemIntents.overlaySettings(context.packageName))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("\u60ac\u6d6e\u7a97\u6743\u9650") }
            OutlinedButton(
                onClick = { SystemIntents.openAccessibilitySettings(context) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("\u65e0\u969c\u788d\u670d\u52a1\u8bbe\u7f6e") }

            // \u5b58\u50a8\u4f4d\u7f6e\uff1a\u53ef\u81ea\u5b9a\u4e49\u6839\u76ee\u5f55\uff0c\u9ed8\u8ba4\u843d\u5728\u4e0b\u8f7d\u76ee\u5f55
            SectionTitle("\u5b58\u50a8\u4f4d\u7f6e")
            Text(
                text = "\u622a\u56fe\u4fdd\u5b58\u6839\u76ee\u5f55\uff08\u6309\u80a1\u7968\u4ee3\u7801\u5206\u6587\u4ef6\u5939\uff09",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = state.storageRoot.ifBlank { StorageUtils.defaultRootPath() },
                    modifier = Modifier.padding(12.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.weight(1f)
                ) { Text("\u9009\u62e9\u6587\u4ef6\u5939") }
                OutlinedButton(
                    onClick = {
                        vm.update { it.copy(storageRoot = "") }
                        vm.save()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("\u6062\u590d\u9ed8\u8ba4") }
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        SystemIntents.allFilesAccessSettings(context.packageName)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("\u6240\u6709\u6587\u4ef6\u8bbf\u95ee\u6743\u9650") }

            // \u6570\u636e\u5907\u4efd/\u6062\u590d\uff1a\u4ec5\u5bfc\u51fa\u6570\u636e\u5e93\u5173\u8054\u4fe1\u606f\uff0c\u4e0d\u542b\u56fe\u7247
            SectionTitle("\u6570\u636e\u5907\u4efd/\u6062\u590d")
            Text(
                text = "\u4ec5\u5907\u4efd\u6570\u636e\u5e93\u5173\u8054\u4fe1\u606f\uff08\u7cfb\u7edf\u8bbe\u7f6e\u3001\u8bb0\u5f55\u3001\u6807\u8bb0\uff09\uff0c\u4e0d\u5305\u542b\u56fe\u7247\u6587\u4ef6\u3002\u6062\u590d\u540e\u8bf7\u81ea\u884c\u628a\u65e7\u56fe\u7247\u6309 {\u4ee3\u7801}/{\u6587\u4ef6\u540d} \u76ee\u5f55\u7ed3\u6784\u653e\u56de\u3002",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        backupPicker.launch("stockshot_backup_$ts.json")
                    },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f)
                ) { Text("\u5907\u4efd\u6570\u636e") }
                OutlinedButton(
                    onClick = {
                        restorePicker.launch(arrayOf("application/json", "*/*"))
                    },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f)
                ) { Text("\u6062\u590d\u6570\u636e") }
            }
            state.opMessage?.let { msg ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // \u8bbe\u7f6e\u5206\u4eab\uff08\u4e8c\u7ef4\u7801\uff09\uff1a\u628a\u5f53\u524d\u8bbe\u7f6e\u751f\u6210\u4e8c\u7ef4\u7801\uff0c\u53e6\u4e00\u53f0\u624b\u673a\u626b\u7801\u4e00\u952e\u5bfc\u5165
            SectionTitle("\u8bbe\u7f6e\u5206\u4eab\uff08\u4e8c\u7ef4\u7801\uff09")
            Text(
                text = "\u751f\u6210\u4e8c\u7ef4\u7801\u540e\uff0c\u5728\u53e6\u4e00\u53f0\u65b0\u88c5\u672c\u7a0b\u5e8f\u7684\u624b\u673a\u4e0a\u626b\u7801\u5373\u53ef\u4e00\u952e\u590d\u5236\u5168\u90e8\u8bbe\u7f6e\uff08\u4e0d\u542b\u5b58\u50a8\u6839\u76ee\u5f55\uff0c\u4e5f\u4e0d\u542b\u8bb0\u5f55/\u6807\u8bb0\uff09\u3002",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { vm.generateShareCode() },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f)
                ) { Text("\u751f\u6210\u4e8c\u7ef4\u7801") }
                OutlinedButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            launchQrScan()
                        } else {
                            cameraPermLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f)
                ) { Text("\u626b\u7801\u5bfc\u5165") }
            }
            FeedbackSettingsSection()

            SectionTitle("\u7248\u672c\u4fe1\u606f")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})  Git ${BuildConfig.GIT_COMMIT}\n${BuildConfig.BUILD_TIME}\nby codex",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = { showVersionHistory = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("版本历史") }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.save() },
                enabled = !usingMajorityRule || majorityPercentValid,
                modifier = Modifier.fillMaxWidth()
            ) { Text("\u4fdd\u5b58") }
        }
    }

    if (showVersionHistory) {
        VersionHistoryDialog(onDismiss = { showVersionHistory = false })
    }

    state.qrShareText?.let { code ->
        val qrBitmap = remember(code) {
            runCatching { QrShareUtils.encodeQrBitmap(code, 720) }.getOrNull()
        }
        AlertDialog(
            onDismissRequest = { vm.consumeShareText() },
            title = { Text("\u8bbe\u7f6e\u4e8c\u7ef4\u7801") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "\u8bbe\u7f6e\u4e8c\u7ef4\u7801",
                            modifier = Modifier.size(240.dp),
                        )
                    } else {
                        Text("\u4e8c\u7ef4\u7801\u751f\u6210\u5931\u8d25")
                    }
                    Text(
                        text = "\u5728\u53e6\u4e00\u53f0\u624b\u673a\u7684\u672c\u7a0b\u5e8f\u8bbe\u7f6e\u9875\u70b9\u201c\u626b\u7801\u5bfc\u5165\u201d\u5373\u53ef\u4e00\u952e\u590d\u5236\u5168\u90e8\u8bbe\u7f6e\u3002",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "\u26a0\ufe0f \u4e8c\u7ef4\u7801\u5305\u542b API Key \u7b49\u654f\u611f\u4fe1\u606f\uff0c\u8bf7\u52ff\u5206\u4eab\u7ed9\u4e0d\u4fe1\u4efb\u7684\u4eba\u3002",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.consumeShareText() }) { Text("\u5173\u95ed") }
            },
        )
    }
}

@Composable
private fun VersionHistoryDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("版本历史") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                VersionHistoryEntry(
                    version = "1.11",
                    changes = listOf(
                        "资金识别可选中位位置或逐列比较，支持整段严格、大部分时刻和信号当列规则。",
                        "默认从左到右检查信号，首个满足即停止；改进粘连点识别并新增无法判断状态。",
                        "指标测试支持缩放截图、完整诊断标注、人工样本保存、分组验收与 ZIP 导出。",
                    ),
                )
                VersionHistoryEntry(
                    version = "1.10",
                    changes = listOf(
                        "版本信息新增 by codex。",
                        "新增优化建议，支持记录、整理、截图附件和独立备份恢复。",
                        "日期选择可通过铅笔输入无数据的历史日期并保存截图，日历仍将无数据日期置灰。",
                    ),
                )
                VersionHistoryEntry(
                    version = "1.9",
                    changes = listOf(
                        "更新最高价增加可读的失败原因，并在主行情源失败时尝试备用行情。",
                        "新增可随时开关的诊断日志，支持查看、复制、导出、分享和清空。",
                    ),
                )
                VersionHistoryEntry(
                    version = "1.8",
                    changes = listOf(
                        "新增“更新最高价”，可设置最大天数（默认 10），将期间最高价更新为卖出价。",
                        "获取当日列表保存到当前选择的日期，并在本次采集选项中提示目标日期。",
                        "新增可自由编辑的备注列；名称及其左侧列固定，右侧列可横向滚动，长备注自动换行并调整行高。",
                        "新增 C、D 安装包，A/B/C/D 四个版本可同时安装，各自使用独立的默认截图目录和无障碍服务名称。",
                    ),
                )
                VersionHistoryEntry(
                    version = "1.7",
                    changes = listOf(
                        "\u8d44\u91d1\u7ebf\u5224\u65ad\u53ea\u5206\u6790\u7ea2\u7eff\u70b9\u5de6\u4fa7\u4e00\u6bb5\u65f6\u95f4\u7684\u6570\u636e\uff0c\u4e0d\u518d\u53d7\u7ea2\u7eff\u70b9\u53f3\u4fa7\u8d70\u52bf\u5f71\u54cd\u3002",
                        "\u65b0\u589e\u201c\u8d44\u91d1\u7ebf\u5de6\u4fa7\u7a97\u53e3\u201d\u9608\u503c\uff08\u9ed8\u8ba4 0.4\uff0c\u5360\u56fe\u8868\u5bbd\u5ea6\u6bd4\u4f8b\uff0c0.1-1.0 \u53ef\u8c03\uff09\u3002",
                        "\u7a97\u53e3\u8d8a\u5c0f\u53ea\u770b\u70b9\u5de6\u4fa7\u8d8a\u77ed\u7684\u6700\u8fd1\u6570\u636e\uff0c\u8d8a\u5927\u8d8a\u63a5\u8fd1\u4ee5\u524d\u7684\u5168\u5bbd\u5ea6\u3002",
                    ),
                )
                VersionHistoryEntry(
                    version = "1.6",
                    changes = listOf(
                        "\u65b0\u589e\u8bc6\u522b\u9608\u503c\u5fae\u8c03\uff1a\u70b9\u5bf9\u9f50\u5bb9\u5dee\u4e0e\u6700\u5c0f\u7f6e\u4fe1\u5ea6\u53ef\u5728\u8bbe\u7f6e\u91cc\u73b0\u573a\u8c03\u6574\u3002",
                        "\u9ed8\u8ba4\u503c\u4e0e\u65e7\u7248\u4e00\u81f4\uff08\u5bb9\u5dee 1.0\u3001\u6700\u5c0f\u7f6e\u4fe1\u5ea6 0\uff09\uff0c\u4e0d\u6539\u53d8\u73b0\u6709\u884c\u4e3a\u3002",
                        "\u529f\u80fd\u6d4b\u8bd5\u7684\u6307\u6807\u8bc6\u522b\u4e0e\u5b9e\u9645\u8dd1\u4efb\u52a1\u4f7f\u7528\u76f8\u540c\u9608\u503c\u3002",
                    ),
                )
                VersionHistoryEntry(
                    version = "1.5",
                    changes = listOf(
                        "\u4fee\u590d\u5e95\u90e8\u7eff\u70b9\u6f0f\u8bc6\u522b\uff1a\u7ea2\u70b9\u951a\u5b9a+\u5bbd\u5bb9\u5dee\u7eff\u5757\u4e3a\u4e3b\u3001\u53cc\u5bbd\u5bb9\u5dee\u5151\u5e95\u3002",
                        "\u8d44\u91d1\u535a\u5f08\u53ef\u9009\u7d2b/\u9ec4\u53cc\u6a21\u5f0f\uff1a\u5f00\u59cb\u91c7\u96c6\u65f6\u9009\u201c\u4ec5\u7d2b\u7ebf\u5728\u7eff\u4e0a\u201d\u6216\u201c\u7d2b\u3001\u9ec4\u4e24\u6761\u90fd\u5728\u7eff\u4e0a\u201d\u3002",
                        "\u4fee\u590d\u4eca\u65e5\u65e0\u6570\u636e\u65f6\u5207\u5230\u5176\u4ed6\u65e5\u671f\u540e\u65e0\u6cd5\u5207\u56de\u4eca\u5929\u3002",
                        "\u8bc6\u522b\u65b0\u589e\u9ec4\u8272\u8f68\u8ff9\u5224\u65ad\uff0c\u4e0e\u7ea2/\u7eff/\u7d2b\u4e0d\u51b2\u7a81\u3002",
                    ),
                )
                VersionHistoryEntry(
                    version = "1.4",
                    changes = listOf(
                        "\u4fee\u590d\u547d\u4e2d\u8bfb\u4ef7\u540e\u5173\u95ed\u5149\u6807\u8bef\u70b9\u6807\u9898\u680f\u3001\u5f39\u51fa\u201c\u6df1\u6e2f\u901a\u201d\u63d0\u793a\u5bfc\u81f4\u70b9\u4e0b\u4e00\u53ea\u5931\u8d25\u3002",
                        "\u5173\u95ed\u5341\u5b57\u5149\u6807\u6539\u7528\u7cfb\u7edf\u8fd4\u56de\u952e\uff0c\u53ef\u5728\u8bbe\u7f6e\u4e2d\u5207\u6362\u4e3a\u70b9\u56fe\u8868\u4e2d\u592e\u6216\u4e0d\u5904\u7406\u3002",
                        "\u65b0\u589e\u201c\u8bfb\u4ef7\u540e\u5ef6\u8fdf\u201d\u8bbe\u7f6e\uff0c\u8bfb\u5230\u4e70\u5165\u4ef7\u540e\u7b49\u5f85\u66f4\u5145\u5206\u518d\u5207\u4e0b\u4e00\u53ea\u3002",
                        "\u529f\u80fd\u6d4b\u8bd5\u65b0\u589e\u201c\u70b9\u51fb\u4e0b\u4e00\u53ea\u201d\uff0c\u53ef\u5355\u72ec\u9a8c\u8bc1\u4e0b\u4e00\u53ea\u6309\u94aeID\u662f\u5426\u6b63\u786e\u3002",
                    ),
                )
                VersionHistoryEntry(
                    version = "1.3",
                    changes = listOf(
                        "\u65b0\u589e\u8bbe\u7f6e\u4e8c\u7ef4\u7801\u4e00\u952e\u5206\u4eab\uff1a\u751f\u6210\u4e8c\u7ef4\u7801\uff0c\u53e6\u4e00\u53f0\u624b\u673a\u626b\u7801\u5373\u53ef\u5bfc\u5165\u5168\u90e8\u8bbe\u7f6e\u3002",
                        "\u4e8c\u7ef4\u7801\u4e0d\u5305\u542b\u5b58\u50a8\u6839\u76ee\u5f55\u4e0e\u8bb0\u5f55/\u6807\u8bb0\uff0c\u5bfc\u5165\u65f6\u53ea\u8986\u76d6\u8bbe\u7f6e\u9879\u3002",
                        "\u6062\u590d\u6570\u636e\u540e\u81ea\u52a8\u68c0\u67e5\u5e76\u5217\u51fa\u5c1a\u672a\u5c31\u4f4d\u7684\u56fe\u7247\uff0c\u63d0\u9192\u624b\u52a8\u653e\u56de\u3002",
                    ),
                )
                VersionHistoryEntry(
                    version = "1.2",
                    changes = listOf(
                        "\u81ea\u52a8\u8bc6\u522b\u7ea2\u7eff\u70b9\u91cd\u5408\uff0c\u547d\u4e2d\u5373\u5728\u6570\u636e\u5e93\u6807\u8bb0\u8be5\u80a1\u7968\u5f53\u5929\u3002",
                        "\u547d\u4e2d\u540e\u5728\u7ea2\u7eff\u70b9\u957f\u6309\u8bfb\u53d6\u4e70\u5165\u4ef7\u5e76\u5199\u5165\u6570\u636e\u5e93\u3002",
                        "\u901a\u7528\u63a7\u4ef6\u6309\u533a\u57df/\u7c7b\u540d/\u5bbd\u9ad8\u6761\u4ef6\u67e5\u627e\u8bfb\u53d6\uff0c\u652f\u6301\u6d4b\u8bd5\u3002",
                        "\u91c7\u96c6\u524d\u5f39\u786e\u8ba4\u6846\uff0c\u53ef\u9009\u62e9\u672c\u6b21\u662f\u5426\u542f\u7528\u8bc6\u522b/\u6807\u8bb0/\u8bfb\u4ef7\u3002",
                        "\u65b0\u589e\u529f\u80fd\u6d4b\u8bd5\u60ac\u6d6e\u5de5\u5177\uff0c\u53ef\u6d4b\u8bd5\u63a7\u4ef6\u67e5\u627e\u4e0e\u6307\u6807\u8bc6\u522b\u3002",
                    ),
                )
                VersionHistoryEntry(
                    version = "1.1",
                    changes = listOf(
                        "图片查看器可在设置中选择左右滑动或上下滑动翻页。",
                        "优化无损 WebP 图片查看器，解决部分安装包浏览时卡顿的问题。",
                        "列表支持长按两行进行区间选择，也支持按住并滑过多行连续选择。",
                        "设置页显示版本、Git 编号和构建时间，并新增版本历史。",
                    ),
                )
                VersionHistoryEntry(
                    version = "1.0",
                    changes = listOf(
                        "首个正式版本：支持悬浮按钮、自动截图、股票行情与盈亏信息。",
                        "截图以无损 WebP 保存，支持自定义存储根目录和按股票代码归档。",
                        "支持日期列表、搜索、标记、多选删除和全屏图片浏览。",
                        "支持仅备份设置、数据库及图片关联信息，并提供可共存的 A/B 安装包。",
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun VersionHistoryEntry(version: String, changes: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "版本 $version",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        changes.forEach { change ->
            Text(
                text = "• $change",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}
