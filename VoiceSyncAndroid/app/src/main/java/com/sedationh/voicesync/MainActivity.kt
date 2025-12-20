package com.sedationh.voicesync

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sedationh.voicesync.ui.theme.VoiceSyncAndroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class MainActivity : ComponentActivity() {
    // 1. 初始化 OkHttp 客户端
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceSyncAndroidTheme {
                // 2. 状态管理
                var targetIp by remember { mutableStateOf("192.168.31.62:4500") } // 👈 记得改成你 Mac 的 IP
                var content by remember { mutableStateOf("") }
                var logMessage by remember { mutableStateOf("等待发送...") }
                val scope = rememberCoroutineScope()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(20.dp)
                            .fillMaxSize()
                    ) {
                        Text(
                            text = "VoiceSync 发送端",
                            style = MaterialTheme.typography.headlineLarge
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))

                        // IP 输入框
                        TextField(
                            value = targetIp,
                            onValueChange = { targetIp = it },
                            label = { Text("Mac IP 地址 (需包含 :4500)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // 内容输入框
                        TextField(
                            value = content,
                            onValueChange = { content = it },
                            label = { Text("输入要同步的文字") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 5
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // 发送按钮
                        Button(
                            onClick = {
                                logMessage = "正在发送..."
                                // 3. 在 IO 线程执行网络请求
                                scope.launch(Dispatchers.IO) {
                                    sendToMac(targetIp, content) { success, msg ->
                                        logMessage = if (success) "同步成功 ✅" else "失败: $msg ❌"
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("立即发送到 Mac")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = logMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (logMessage.contains("成功")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    // 4. 发送逻辑 (对应 Plan 1.4)
    private fun sendToMac(ipPort: String, text: String, onResult: (Boolean, String) -> Unit) {
        if (text.isEmpty()) {
            onResult(false, "内容不能为空")
            return
        }

        // 确保 URL 格式正确
        val url = if (ipPort.startsWith("http")) "$ipPort/sync" else "http://$ipPort/sync"
        
        val request = Request.Builder()
            .url(url)
            .post(text.toRequestBody())
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("VoiceSync", "请求失败: ${e.message}")
                onResult(false, e.message ?: "未知错误")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        onResult(true, "OK")
                    } else {
                        onResult(false, "服务器返回: ${it.code}")
                    }
                }
            }
        })
    }
}