package com.sedationh.voicesync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sedationh.voicesync.ui.theme.VoiceSyncAndroidTheme
import kotlinx.coroutines.*
import okhttp3.*
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceSyncAndroidTheme {
                var targetIp by remember { mutableStateOf("192.168.31.62:$defaultPort") } // 改成你的 IP
                var content by remember { mutableStateOf("") }
                var logMessage by remember { mutableStateOf("等待输入...") }
                var autoClearEnabled by remember { mutableStateOf(true) } // 4.2 自动清除开关
                var syncRecords by remember { mutableStateOf(listOf<SyncRecord>()) } // 同步记录
                val scope = rememberCoroutineScope()
                
                val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding).padding(16.dp).fillMaxSize()) {
                        // 标题
                        Text(
                            text = if (BuildConfig.DEBUG) "VoiceSync (Dev)" else "VoiceSync",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))

                        // ========== 1. 语音输入区（最上面）==========
                        TextField(
                            value = content,
                            onValueChange = { newText ->
                                content = newText
                                logMessage = "正在录入..."
                                
                                // 只要用户在操作，就取消所有的自动任务
                                debounceJob?.cancel()
                                clearJob?.cancel() 
                                
                                // --- 自动发送逻辑 (Debounce) ---
                                debounceJob = scope.launch {
                                    delay(2000) // 等待 2 秒停顿
                                    if (content.isNotEmpty()) {
                                        logMessage = "检测到停顿，自动同步中..."
                                        sendToMac(targetIp, content) { success, msg ->
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
                                                
                                                // --- 4.1 自动清除逻辑（受开关控制）---
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
                            },
                            label = { Text("语音输入区 (停顿2秒自动同步)") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 6
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // 状态信息
                        Text(
                            text = logMessage, 
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // ========== 2. 操作按钮区（中间，方便点击）==========
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                // 自动清空开关
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("自动清空", style = MaterialTheme.typography.bodyLarge)
                                    Switch(
                                        checked = autoClearEnabled,
                                        onCheckedChange = { autoClearEnabled = it }
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // 手动清空按钮
                                Button(
                                    onClick = { 
                                        content = ""
                                        logMessage = "手动已清空"
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("手动清空")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // ========== 3. IP地址设置（下面）==========
                        TextField(
                            value = targetIp,
                            onValueChange = { targetIp = it },
                            label = { Text("Mac IP 地址") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(12.dp))

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
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // 使用 LazyColumn 显示记录列表
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(syncRecords) { record ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
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
                        }
                    }
                }
            }
        }
    }

    private fun sendToMac(ipPort: String, text: String, onResult: (Boolean, String) -> Unit) {
        val url = if (ipPort.startsWith("http")) "$ipPort/sync" else "http://$ipPort/sync"
        val request = Request.Builder().url(url).post(text.toRequestBody()).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onResult(false, e.message ?: "网络错误") }
            override fun onResponse(call: Call, response: Response) {
                response.use { if (it.isSuccessful) onResult(true, "OK") }
            }
        })
    }
}