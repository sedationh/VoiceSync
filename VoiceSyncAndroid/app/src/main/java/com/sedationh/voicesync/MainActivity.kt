package com.sedationh.voicesync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sedationh.voicesync.ui.theme.VoiceSyncAndroidTheme
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class MainActivity : ComponentActivity() {
    private val client = OkHttpClient()
    // 用于控制自动发送的协程任务
    private var debounceJob: Job? = null 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceSyncAndroidTheme {
                var targetIp by remember { mutableStateOf("192.168.31.62:4500") } // 改成你的 IP
                var content by remember { mutableStateOf("") }
                var logMessage by remember { mutableStateOf("等待输入...") }
                val scope = rememberCoroutineScope()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding).padding(20.dp).fillMaxSize()) {
                        Text(text = "VoiceSync 自动同步端", style = MaterialTheme.typography.headlineMedium)
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        TextField(
                            value = targetIp,
                            onValueChange = { targetIp = it },
                            label = { Text("Mac IP 地址") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // 核心：这里的 onValueChange 包含了自动发送逻辑
                        TextField(
                            value = content,
                            onValueChange = { newText ->
                                content = newText
                                logMessage = "正在录入..."
                                
                                // --- 自动发送逻辑 (Debounce) ---
                                debounceJob?.cancel() // 只要在动，就取消上一个计时器
                                debounceJob = scope.launch {
                                    delay(2000) // 等待 2 秒停顿
                                    if (content.isNotEmpty()) {
                                        logMessage = "检测到停顿，自动同步中..."
                                        sendToMac(targetIp, content) { success, msg ->
                                            logMessage = if (success) "自动同步成功 ✅" else "同步失败: $msg"
                                        }
                                    }
                                }
                            },
                            label = { Text("语音输入区 (停顿2秒自动同步)") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 8
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // 手动清除按钮
                        Button(
                            onClick = { content = ""; logMessage = "已清空" },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("手动清空输入框")
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(text = logMessage, style = MaterialTheme.typography.bodySmall)
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