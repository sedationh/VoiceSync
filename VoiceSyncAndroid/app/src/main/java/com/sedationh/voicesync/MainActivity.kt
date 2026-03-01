package com.sedationh.voicesync

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import com.sedationh.voicesync.ui.theme.VoiceSyncAndroidTheme
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

// 数据类：记录同步历史
data class SyncRecord(
    val timestamp: String,
    val content: String,
    val success: Boolean,
    val message: String,
    val type: String = "text", // text 或 image
    val mimeType: String? = null // 图片MIME类型
)

class MainActivity : ComponentActivity() {
    private val client = OkHttpClient()
    // 用于控制自动发送的协程任务
    private var debounceJob: Job? = null 
    private var clearJob: Job? = null // 4.1 用于控制自动清空的协程任务
    
    // 从 BuildConfig 获取端口号
    private val defaultPort = BuildConfig.SYNC_PORT
    
    /**
     * 根据文本长度计算智能延迟时间（毫秒）
     * 算法设计：
     * - 0-25字：2秒
     * - 25-100字：2-4秒（线性增长）
     * - 100字以上：4秒（封顶）
     */
    private fun calculateSmartDelay(textLength: Int): Long {
        return when {
            textLength <= 25 -> 2000L  // 2秒
            textLength <= 100 -> {
                // 2秒 + (字数-25) * (2秒/75字) = 2-4秒
                2000L + ((textLength - 25) * 2000L / 75).toLong()
            }
            else -> 4000L  // 4秒封顶
        }
    }
    
    /**
     * 读取图片URI并转换为Base64编码字符串
     * @param uri 图片URI
     * @return Pair<Base64字符串, MIME类型> 或 null（如果失败）
     */
    private fun imageUriToBase64(uri: Uri): Pair<String, String>? {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val bytes = inputStream.readBytes()
            inputStream.close()
            
            // 获取MIME类型
            val mimeType = contentResolver.getType(uri) ?: "image/*"
            
            // 转换为Base64
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return Pair(base64, mimeType)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // 初始化 IP 历史管理器
        val ipHistoryManager = IpHistoryManager(this)
        
        // 初始化设置管理器
        val settingsManager = SettingsManager(this)
        
        setContent {
            VoiceSyncAndroidTheme(dynamicColor = false) { // 禁用动态颜色，使用固定浅绿色主题
                // 通知权限请求（Android 13+）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        if (isGranted) {
                            VoiceSyncService.start(this@MainActivity, ipHistoryManager.getLatestIp() ?: "")
                        }
                    }
                    
                    LaunchedEffect(Unit) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            VoiceSyncService.start(this@MainActivity, ipHistoryManager.getLatestIp() ?: "")
                        }
                    }
                } else {
                    LaunchedEffect(Unit) {
                        VoiceSyncService.start(this@MainActivity, ipHistoryManager.getLatestIp() ?: "")
                    }
                }
                
                // 从历史记录中加载最近使用的 IP，如果没有则使用默认值
                val defaultIp = ipHistoryManager.getLatestIp() ?: "192.168.31.62:$defaultPort"
                var targetIp by remember { mutableStateOf(defaultIp) }
                var content by remember { mutableStateOf("") }
                var logMessage by remember { mutableStateOf("等待输入...") }
                var autoSendEnabled by remember { mutableStateOf(settingsManager.getAutoSendEnabled()) } // 自动发送开关（从持久化存储加载）
                var autoClearEnabled by remember { mutableStateOf(settingsManager.getAutoClearEnabled()) } // 自动清除开关（从持久化存储加载）
                var autoEnterEnabled by remember { mutableStateOf(settingsManager.getAutoEnterEnabled()) } // 远程回车开关（从持久化存储加载）
                var isProductionMode by remember { mutableStateOf(false) } // 生产模式开关（默认开发模式）
                var currentDelay by remember { mutableStateOf(2000L) } // 当前延迟时间（毫秒）
                var syncRecords by remember { mutableStateOf(listOf<SyncRecord>()) } // 同步记录
                var showIpHistory by remember { mutableStateOf(false) } // 是否显示 IP 历史列表
                var showDeviceDiscovery by remember { mutableStateOf(false) } // 是否显示设备发现对话框
                var updateResult by remember { mutableStateOf<UpdateChecker.UpdateResult?>(null) } // 更新检查结果
                val scope = rememberCoroutineScope()
                
                // 输入框焦点请求器
                val focusRequester = remember { FocusRequester() }
                val lifecycleOwner = LocalLifecycleOwner.current
                
                // 监听生命周期，当应用进入前台时自动聚焦
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            // 延迟一点确保 UI 完全渲染
                            scope.launch {
                                delay(100)
                                try {
                                    focusRequester.requestFocus()
                                } catch (e: Exception) {
                                    // 忽略焦点请求失败
                                }
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }
                
                val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                
                // 图片选择器
                val imagePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    uri?.let {
                        logMessage = "处理图片中..."
                        scope.launch(Dispatchers.IO) {
                            val result = imageUriToBase64(it)
                            withContext(Dispatchers.Main) {
                                if (result != null) {
                                    val (base64, mimeType) = result
                                    logMessage = "发送图片中..."
                                    sendImageToMac(targetIp, base64, mimeType) { success, msg ->
                                        val time = dateFormat.format(Date())
                                        val record = SyncRecord(
                                            timestamp = time,
                                            content = if (success) "图片 (${base64.length / 1024}KB)" else "图片发送失败",
                                            success = success,
                                            message = msg,
                                            type = "image",
                                            mimeType = mimeType
                                        )
                                        syncRecords = listOf(record) + syncRecords
                                        
                                        if (success) {
                                            logMessage = "图片发送成功 ✅"
                                            ipHistoryManager.addOrUpdateIp(targetIp)
                                        } else {
                                            logMessage = "图片发送失败: $msg"
                                        }
                                    }
                                } else {
                                    logMessage = "图片处理失败"
                                }
                            }
                        }
                    }
                }
                
                // 端口切换函数
                fun togglePort() {
                    val currentIp = targetIp.substringBeforeLast(":")
                    val newPort = if (isProductionMode) "4500" else "4501"
                    targetIp = "$currentIp:$newPort"
                }
                
                // 监听 targetIp 变化，更新通知
                LaunchedEffect(targetIp) {
                    VoiceSyncService.start(this@MainActivity, targetIp)
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LazyColumn(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(horizontal = 20.dp)
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                            // 标题和构建时间
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (BuildConfig.DEBUG) "VoiceSync (Dev)" else "VoiceSync",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "v${BuildConfig.VERSION_NAME}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                // 检查更新按钮
                                OutlinedButton(
                                    onClick = {
                                        logMessage = "正在检查更新..."
                                        scope.launch {
                                            val result = UpdateChecker.check(BuildConfig.VERSION_NAME)
                                            if (result == null) {
                                                logMessage = "检查更新失败，请检查网络连接"
                                            } else if (result.hasUpdate) {
                                                updateResult = result
                                                logMessage = "发现新版本: v${result.latestVersion}"
                                            } else {
                                                logMessage = "已是最新版本 v${result.currentVersion}"
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("检查更新", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))

                            // ========== 1. 发送按钮区（最上面，方便点击）==========
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 左边：清空和图片按钮（上下排列）
                                Column(
                                    modifier = Modifier.weight(0.5f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // 清空按钮
                                    OutlinedButton(
                                        onClick = { 
                                            content = ""
                                            logMessage = "已清空"
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Text("清空", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    
                                    // 图片选择按钮（只显示图标）
                                    OutlinedButton(
                                        onClick = { 
                                            imagePickerLauncher.launch("image/*")
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Text("📷", style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                                
                                // 右边：发送按钮组（上下排列）
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    // 发送按钮（上面，使用频率较低）
                                    OutlinedButton(
                                        onClick = {
                                            if (content.isNotEmpty()) {
                                                logMessage = "发送中..."
                                                sendToMac(targetIp, content, false) { success, msg ->
                                                    val time = dateFormat.format(Date())
                                                    val record = SyncRecord(
                                                        timestamp = time,
                                                        content = content,
                                                        success = success,
                                                        message = msg
                                                    )
                                                    syncRecords = listOf(record) + syncRecords
                                                    
                                                    if (success) {
                                                        logMessage = "发送成功 ✅"
                                                        ipHistoryManager.addOrUpdateIp(targetIp)
                                                        if (autoClearEnabled) {
                                                            content = ""
                                                            logMessage = "发送成功 ✅ 已清空"
                                                        }
                                                    } else {
                                                        logMessage = "发送失败: $msg"
                                                    }
                                                }
                                            } else {
                                                logMessage = "内容为空"
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = content.isNotEmpty(),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("发送", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    
                                    // 发送+回车按钮（下面，使用频率更高，更容易按到）
                                    Button(
                                        onClick = {
                                            if (content.isNotEmpty()) {
                                                logMessage = "发送中..."
                                                sendToMac(targetIp, content, true) { success, msg ->
                                                    val time = dateFormat.format(Date())
                                                    val record = SyncRecord(
                                                        timestamp = time,
                                                        content = content,
                                                        success = success,
                                                        message = msg
                                                    )
                                                    syncRecords = listOf(record) + syncRecords
                                                    
                                                    if (success) {
                                                        logMessage = "发送成功 ✅ (含回车)"
                                                        ipHistoryManager.addOrUpdateIp(targetIp)
                                                        if (autoClearEnabled) {
                                                            content = ""
                                                            logMessage = "发送成功 ✅ (含回车) 已清空"
                                                        }
                                                    } else {
                                                        logMessage = "发送失败: $msg"
                                                    }
                                                }
                                            } else {
                                                logMessage = "内容为空"
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = content.isNotEmpty(),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Text("发送 + 回车", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            // 状态信息
                            Text(
                                text = logMessage, 
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))

                            // ========== 2. 语音输入区 ==========
                            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                            val maxHeightDp = (configuration.screenHeightDp * 0.35f).dp
                            
                            TextField(
                                value = content,
                                onValueChange = { newText ->
                                    content = newText
                                    logMessage = "正在录入..."
                                    
                                    // 只要用户在操作，就取消所有的自动任务
                                    debounceJob?.cancel()
                                    clearJob?.cancel() 
                                    
                                    // --- 自动发送逻辑 (Debounce) ---
                                    // 只有当自动发送开关打开时才执行自动发送
                                    if (autoSendEnabled) {
                                        // 根据当前文本长度计算智能延迟
                                        currentDelay = calculateSmartDelay(newText.length)
                                        
                                        debounceJob = scope.launch {
                                            delay(currentDelay) // 使用智能延迟
                                            if (content.isNotEmpty()) {
                                                logMessage = "检测到停顿，自动同步中..."
                                                sendToMac(targetIp, content, autoEnterEnabled) { success, msg ->
                                                    val time = dateFormat.format(Date())
                                                    val record = SyncRecord(
                                                        timestamp = time,
                                                        content = content,
                                                        success = success,
                                                        message = msg
                                                    )
                                                    syncRecords = listOf(record) + syncRecords // 新记录在最前
                                                    
                                                    if (success) {
                                                        logMessage = "自动同步成功 ✅" + if (autoClearEnabled) "，3秒后自动清空" else ""
                                                        
                                                        // 保存成功的 IP 到历史
                                                        ipHistoryManager.addOrUpdateIp(targetIp)
                                                        
                                                        // --- 自动清除逻辑（受开关控制）---
                                                        if (autoClearEnabled) {
                                                            clearJob = scope.launch {
                                                                delay(3000) // 等待 3 秒
                                                                content = "" // 执行清空
                                                                logMessage = "内容已自动清空，请继续说话"
                                                            }
                                                        }
                                                    } else {
                                                        logMessage = "同步失败: $msg"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                label = { 
                                    Text(
                                        if (autoSendEnabled) 
                                            "语音输入区 (停顿${String.format("%.1f", currentDelay / 1000.0)}秒自动同步)" 
                                        else 
                                            "语音输入区 (手动发送模式)"
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = maxHeightDp)
                                    .focusRequester(focusRequester),
                                minLines = 5,
                                maxLines = Int.MAX_VALUE
                            )
                        }

                        item {
                            // ========== 3. 设置选项区（下面）==========
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    // 自动发送开关
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("自动发送", style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                text = "停顿后自动发送内容",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                        Switch(
                                            checked = autoSendEnabled,
                                            onCheckedChange = { 
                                                autoSendEnabled = it
                                                settingsManager.setAutoSendEnabled(it) // 保存到持久化存储
                                                if (!it) {
                                                    debounceJob?.cancel()
                                                    clearJob?.cancel()
                                                }
                                            }
                                        )
                                    }
                                    
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    
                                    // 自动清空开关 (始终可用，不再依赖自动发送)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("发送后清空", style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                text = "发送成功后自动清空输入框",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                        Switch(
                                            checked = autoClearEnabled,
                                            onCheckedChange = { 
                                                autoClearEnabled = it
                                                settingsManager.setAutoClearEnabled(it) // 保存到持久化存储
                                            }
                                        )
                                    }
                                    
                                    // 自动发送时的回车选项（只在自动发送开启时显示）
                                    if (autoSendEnabled) {
                                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                        
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("自动发送含回车", style = MaterialTheme.typography.bodyMedium)
                                                Text(
                                                    text = "自动发送时同时发送回车键",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                )
                                            }
                                            Switch(
                                                checked = autoEnterEnabled,
                                                onCheckedChange = { 
                                                    autoEnterEnabled = it
                                                    settingsManager.setAutoEnterEnabled(it) // 保存到持久化存储
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            // ========== 3. IP地址设置（下面）==========
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    // 端口模式切换
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = if (isProductionMode) "🚀 生产模式 (4500)" else "🔧 开发模式 (4501)",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                            Text(
                                                text = "快速切换端口",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                            )
                                        }
                                        Switch(
                                            checked = isProductionMode,
                                            onCheckedChange = { 
                                                isProductionMode = it
                                                togglePort()
                                                logMessage = if (it) "已切换到生产模式 (4500)" else "已切换到开发模式 (4501)"
                                            }
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    // IP地址输入框
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextField(
                                            value = targetIp,
                                            onValueChange = { targetIp = it },
                                            label = { Text("Mac IP 地址") },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true
                                        )
                                        
                                        Spacer(modifier = Modifier.width(8.dp))
                                        
                                        // 扫描设备按钮
                                        IconButton(
                                            onClick = { showDeviceDiscovery = true },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
                                                contentDescription = "扫描设备",
                                                tint = MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                        
                                        // IP 历史按钮
                                        IconButton(
                                            onClick = { showIpHistory = !showIpHistory },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (showIpHistory) 
                                                    androidx.compose.material.icons.Icons.Default.KeyboardArrowUp 
                                                else 
                                                    androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
                                                contentDescription = "历史记录",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    
                                    // IP 历史列表（展开/收起）
                                    if (showIpHistory && ipHistoryManager.historyList.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                            )
                                        ) {
                                            Column(modifier = Modifier.padding(8.dp)) {
                                                Text(
                                                    text = "IP 历史记录",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                
                                                Spacer(modifier = Modifier.height(4.dp))
                                                
                                                ipHistoryManager.historyList.forEach { history ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 4.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        // IP 地址（可点击选择）
                                                        Text(
                                                            text = history.ipAddress,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .clickable {
                                                                    targetIp = history.ipAddress
                                                                    showIpHistory = false
                                                                    logMessage = "已选择: ${history.ipAddress}"
                                                                }
                                                        )
                                                        
                                                        // 删除按钮
                                                        IconButton(
                                                            onClick = {
                                                                ipHistoryManager.deleteIp(history.ipAddress)
                                                                logMessage = "已删除: ${history.ipAddress}"
                                                            },
                                                            modifier = Modifier.size(32.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = androidx.compose.material.icons.Icons.Default.Delete,
                                                                contentDescription = "删除",
                                                                tint = MaterialTheme.colorScheme.error,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            // ========== 4. 同步记录列表（最下面）==========
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "同步记录 (${syncRecords.size})",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                
                                // 清空记录按钮
                                OutlinedButton(
                                    onClick = { 
                                        syncRecords = emptyList()
                                        logMessage = "记录已清空"
                                    }
                                ) {
                                    Text("清空记录")
                                }
                            }
                        }
                        
                        items(syncRecords) { record ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // 点击记录恢复内容到输入框
                                        content = record.content
                                        logMessage = "已恢复内容"
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (record.success) 
                                        MaterialTheme.colorScheme.primaryContainer
                                    else 
                                        MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = record.timestamp,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (record.success)
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            else
                                                MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Text(
                                            text = if (record.success) "✅ 成功" else "❌ 失败",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (record.success)
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            else
                                                MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = record.content,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (record.success)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    if (!record.success) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "错误: ${record.message}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }
                        }
                        
                        item {
                            Spacer(modifier = Modifier.height(20.dp))
                        }
                    }
                }
                
                // 更新对话框
                updateResult?.let { result ->
                    AlertDialog(
                        onDismissRequest = { updateResult = null },
                        title = { Text("发现新版本 v${result.latestVersion}") },
                        text = {
                            Column {
                                Text("当前版本：v${result.currentVersion}")
                                Text("最新版本：v${result.latestVersion}")
                                if (result.releaseNotes.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("更新说明：", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        result.releaseNotes.take(200) + if (result.releaseNotes.length > 200) "..." else "",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "⚠️ 如安装时提示「签名不一致」，请先卸载旧版本再安装。\n（设置不会丢失）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(result.downloadUrl)
                                    )
                                    startActivity(intent)
                                    updateResult = null
                                }
                            ) {
                                Text("下载更新")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { updateResult = null }) {
                                Text("稍后再说")
                            }
                        }
                    )
                }
                
                // 设备发现对话框
                if (showDeviceDiscovery) {
                    DeviceDiscoveryDialog(
                        onDismiss = { showDeviceDiscovery = false },
                        onDeviceSelected = { address ->
                            targetIp = address
                            logMessage = "已选择设备: $address"
                        }
                    )
                }
            }
        }
    }
    
    private fun sendImageToMac(ipPort: String, base64Image: String, mimeType: String, onResult: (Boolean, String) -> Unit) {
        val url = if (ipPort.startsWith("http")) "$ipPort/sync" else "http://$ipPort/sync"
        
        // 构建 JSON 数据
        val json = """
            {
                "type": "image",
                "content": "$base64Image",
                "mimeType": "$mimeType",
                "timestamp": ${System.currentTimeMillis() / 1000}
            }
        """.trimIndent()
        
        val requestBody = json.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder().url(url).post(requestBody).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onResult(false, e.message ?: "网络错误") }
            override fun onResponse(call: Call, response: Response) {
                response.use { if (it.isSuccessful) onResult(true, "OK") }
            }
        })
    }

    private fun sendToMac(ipPort: String, text: String, autoEnter: Boolean = false, onResult: (Boolean, String) -> Unit) {
        val url = if (ipPort.startsWith("http")) "$ipPort/sync" else "http://$ipPort/sync"
        
        // 构建 JSON 数据
        val json = """
            {
                "type": "text",
                "content": "${text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}",
                "timestamp": ${System.currentTimeMillis() / 1000},
                "autoEnter": $autoEnter
            }
        """.trimIndent()
        
        val requestBody = json.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder().url(url).post(requestBody).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onResult(false, e.message ?: "网络错误") }
            override fun onResponse(call: Call, response: Response) {
                response.use { if (it.isSuccessful) onResult(true, "OK") }
            }
        })
    }
}

/**
 * 设备发现对话框
 * 
 * @param onDismiss 关闭对话框的回调
 * @param onDeviceSelected 选择设备后的回调，参数为设备的完整地址（IP:Port）
 */
@Composable
fun DeviceDiscoveryDialog(
    onDismiss: () -> Unit,
    onDeviceSelected: (String) -> Unit
) {
    var devices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val serviceDiscovery = remember { ServiceDiscovery(context) }
    val scope = rememberCoroutineScope()
    
    // 启动设备扫描
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                serviceDiscovery.discoverDevices().collect { discoveredDevices ->
                    devices = discoveredDevices
                    isScanning = false
                }
            } catch (e: Exception) {
                errorMessage = "扫描失败: ${e.message}"
                isScanning = false
            }
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("扫描局域网设备")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 400.dp)
            ) {
                when {
                    // 显示错误信息
                    errorMessage != null -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "❌",
                                style = MaterialTheme.typography.displayMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = errorMessage ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    // 正在扫描且没有设备
                    isScanning && devices.isEmpty() -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "正在扫描局域网...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "请确保 Mac 端已启动",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                    // 扫描完成但没有找到设备
                    !isScanning && devices.isEmpty() -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "📡",
                                style = MaterialTheme.typography.displayMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "未发现设备",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "请确保：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "• Mac 端已启动 VoiceSync",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "• 连接同一 Wi-Fi",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "• 防火墙未屏蔽",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 找到设备，显示列表
                    else -> {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(devices) { device ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onDeviceSelected(device.address)
                                            onDismiss()
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            // 设备名称
                                            Text(
                                                text = device.displayName,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            // IP 地址
                                            Text(
                                                text = device.address,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                                ),
                                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                            )
                                        }
                                        
                                        // 图标指示可点击
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
                                            contentDescription = "选择",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                        
                        // 底部提示
                        if (isScanning) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "继续扫描中...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}