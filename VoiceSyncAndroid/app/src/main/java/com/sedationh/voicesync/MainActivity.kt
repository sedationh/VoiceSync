package com.sedationh.voicesync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.unit.dp
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
    val message: String
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // 初始化 IP 历史管理器
        val ipHistoryManager = IpHistoryManager(this)
        
        setContent {
            VoiceSyncAndroidTheme {
                // 从历史记录中加载最近使用的 IP，如果没有则使用默认值
                val defaultIp = ipHistoryManager.getLatestIp() ?: "192.168.31.62:$defaultPort"
                var targetIp by remember { mutableStateOf(defaultIp) }
                var content by remember { mutableStateOf("") }
                var logMessage by remember { mutableStateOf("等待输入...") }
                var autoSendEnabled by remember { mutableStateOf(true) } // 自动发送开关
                var autoClearEnabled by remember { mutableStateOf(true) } // 4.2 自动清除开关
                var autoEnterEnabled by remember { mutableStateOf(false) } // 远程回车开关
                var isProductionMode by remember { mutableStateOf(false) } // 生产模式开关（默认开发模式）
                var currentDelay by remember { mutableStateOf(2000L) } // 当前延迟时间（毫秒）
                var syncRecords by remember { mutableStateOf(listOf<SyncRecord>()) } // 同步记录
                var showIpHistory by remember { mutableStateOf(false) } // 是否显示 IP 历史列表
                val scope = rememberCoroutineScope()
                
                val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                
                // 端口切换函数
                fun togglePort() {
                    val currentIp = targetIp.substringBeforeLast(":")
                    val newPort = if (isProductionMode) "4500" else "4501"
                    targetIp = "$currentIp:$newPort"
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
                                Text(
                                    text = if (BuildConfig.DEBUG) "VoiceSync (Dev)" else "VoiceSync",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "构建: ${BuildConfig.BUILD_TIME}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))

                            // ========== 1. 发送按钮区（最上面，方便点击）==========
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 清空按钮（左边）
                                OutlinedButton(
                                    onClick = { 
                                        content = ""
                                        logMessage = "已清空"
                                    },
                                    modifier = Modifier.weight(0.5f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                                ) {
                                    Text("清空", style = MaterialTheme.typography.bodyMedium)
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
                                    .heightIn(max = maxHeightDp),
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
                                            onCheckedChange = { autoClearEnabled = it }
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
                                                onCheckedChange = { autoEnterEnabled = it }
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
            }
        }
    }

    private fun sendToMac(ipPort: String, text: String, autoEnter: Boolean = false, onResult: (Boolean, String) -> Unit) {
        val url = if (ipPort.startsWith("http")) "$ipPort/sync" else "http://$ipPort/sync"
        
        // 构建 JSON 数据
        val json = """
            {
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