# 🔍 VoiceSync 设备自动发现技术方案

> **目标**：实现 Mac 和 Android 设备在局域网内的自动发现，无需手动输入 IP 地址

**版本**：v1.0  
**日期**：2026-03-01  
**优先级**：P1

---

## 📋 目录

- [1. 背景与目标](#1-背景与目标)
- [2. 技术选型](#2-技术选型)
- [3. 架构设计](#3-架构设计)
- [4. 实现方案](#4-实现方案)
- [5. 接口设计](#5-接口设计)
- [6. 用户体验](#6-用户体验)
- [7. 测试方案](#7-测试方案)
- [8. 风险与注意事项](#8-风险与注意事项)

---

## 1. 背景与目标

### 1.1 当前痛点

- ❌ 用户需要在 Mac 端查看 IP 地址
- ❌ 手动在 Android 端输入 IP 地址
- ❌ IP 地址变化后需要重新配置
- ❌ 配置门槛高，影响用户体验

### 1.2 目标

- ✅ Android 端自动扫描局域网内的 Mac 设备
- ✅ 显示可用设备列表（设备名 + IP）
- ✅ 点击即可连接，无需手动输入
- ✅ 保留手动输入作为备选方案

---

## 2. 技术选型

### 2.1 方案对比

| 方案 | 优势 | 劣势 | 评分 |
|------|------|------|------|
| **mDNS/Bonjour** | • 系统原生支持<br>• 工业标准<br>• 自动广播和发现 | • 需要同一局域网 | ⭐⭐⭐⭐⭐ |
| **UDP 广播** | • 实现简单 | • 需要自定义协议<br>• 部分路由器屏蔽<br>• 安全性差 | ⭐⭐⭐ |
| **蓝牙发现** | • 无需 Wi-Fi | • 传输速度慢<br>• 距离限制 | ⭐⭐ |
| **二维码扫描** | • 实现简单 | • 仍需手动操作<br>• 非自动化 | ⭐⭐ |

### 2.2 最终选择：mDNS/Bonjour

**理由**：
1. **原生支持**：macOS 和 Android 都有成熟的 API
2. **工业标准**：AirDrop、打印机发现、Chromecast 都使用此技术
3. **零配置**：无需路由器配置，自动工作
4. **安全可靠**：系统级服务，稳定性高

---

## 3. 架构设计

### 3.1 整体架构

```
┌──────────────────────────────────────────────────────────────┐
│                        局域网环境                              │
│                                                               │
│  ┌─────────────────────┐         ┌─────────────────────┐    │
│  │   Mac 设备 (服务端)  │         │  Android 设备 (客户端) │    │
│  │                     │         │                     │    │
│  │  ┌──────────────┐   │         │  ┌──────────────┐   │    │
│  │  │ NetService   │   │         │  │ NsdManager   │   │    │
│  │  │ (Bonjour)    │   │         │  │             │   │    │
│  │  └──────┬───────┘   │         │  └──────┬───────┘   │    │
│  │         │           │         │         │           │    │
│  │         │ 广播      │         │         │ 扫描      │    │
│  │         ▼           │         │         ▼           │    │
│  │  _voicesync._tcp.   │◄────────┤  NSD 发现服务       │    │
│  │                     │  mDNS   │                     │    │
│  │  设备名: VoiceSync-Mac│         │  显示设备列表       │    │
│  │  IP: 192.168.1.100 │         │  ├─ Mac-1 (IP:Port)│    │
│  │  Port: 4500        │         │  ├─ Mac-2 (IP:Port)│    │
│  │                     │         │  └─ Mac-3 (IP:Port)│    │
│  └─────────────────────┘         └─────────────────────┘    │
│                                                               │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 角色定位

| 设备 | 角色 | 职责 | API |
|------|------|------|-----|
| **Mac** | 服务端（Server）| • 广播服务存在<br>• 提供 HTTP API<br>• 被动等待连接 | NetService |
| **Android** | 客户端（Client）| • 扫描可用服务<br>• 解析 IP+Port<br>• 主动建立连接 | NsdManager |

### 3.3 服务标识

- **服务类型**：`_voicesync._tcp.`
  - `_voicesync`：自定义服务名称
  - `_tcp`：使用 TCP 协议（HTTP 基于 TCP）
  - `.`：本地域名后缀

- **服务名称**：`VoiceSync-{设备名}`
  - 示例：`VoiceSync-MacBook-Pro`, `VoiceSync-iMac`

- **端口号**：`4500`（生产环境）/ `4501`（开发环境）

---

## 4. 实现方案

### 4.1 Mac 端实现

#### 4.1.1 文件结构

```
VoiceSyncMac/VoiceSyncMac/
├── ServiceBroadcaster.swift    (新建)
├── VoiceSyncMacApp.swift       (修改)
└── ContentView.swift            (修改 - 可选)
```

#### 4.1.2 核心代码：ServiceBroadcaster.swift

```swift
import Foundation

/// Bonjour 服务广播管理器
class ServiceBroadcaster: NSObject, ObservableObject {
    // MARK: - Properties
    private var service: NetService?
    
    @Published var isPublishing = false
    @Published var lastError: String?
    
    // MARK: - Public Methods
    
    /// 开始广播服务
    /// - Parameters:
    ///   - port: 监听端口号
    ///   - deviceName: 设备名称（可选）
    func startBroadcast(port: Int, deviceName: String? = nil) {
        stopBroadcast() // 先停止已有服务
        
        // 生成服务名称（如果未提供则使用系统主机名）
        let serviceName = deviceName ?? "VoiceSync-\(Host.current().localizedName ?? "Mac")"
        
        // 创建 NetService 实例
        service = NetService(domain: "local.", 
                            type: "_voicesync._tcp.", 
                            name: serviceName, 
                            port: Int32(port))
        
        service?.delegate = self
        service?.publish()
        
        print("📡 开始广播服务: \(serviceName) 在端口 \(port)")
    }
    
    /// 停止广播服务
    func stopBroadcast() {
        service?.stop()
        service = nil
        isPublishing = false
        print("🛑 停止广播服务")
    }
}

// MARK: - NetServiceDelegate

extension ServiceBroadcaster: NetServiceDelegate {
    func netServiceDidPublish(_ sender: NetService) {
        isPublishing = true
        lastError = nil
        print("✅ 服务广播成功: \(sender.name)")
    }
    
    func netService(_ sender: NetService, didNotPublish errorDict: [String : NSNumber]) {
        isPublishing = false
        lastError = "广播失败: \(errorDict)"
        print("❌ 服务广播失败: \(errorDict)")
    }
    
    func netServiceDidStop(_ sender: NetService) {
        isPublishing = false
        print("⏹️ 服务已停止")
    }
}
```

#### 4.1.3 集成到 AppState

```swift
class AppState: ObservableObject {
    static let shared = AppState()
    
    let server = HttpServer()
    var syncManager = SyncManager()
    let broadcaster = ServiceBroadcaster() // 新增
    
    // ...
    
    private init() {
        localIP = getLocalIPAddress() ?? "未知"
        startServer()
        startBroadcast() // 启动广播
    }
    
    func startBroadcast() {
        broadcaster.startBroadcast(port: Int(AppConfig.port))
    }
}
```

### 4.2 Android 端实现

#### 4.2.1 文件结构

```
app/src/main/java/com/sedationh/voicesync/
├── ServiceDiscovery.kt      (新建)
└── MainActivity.kt           (修改)
```

#### 4.2.2 核心代码：ServiceDiscovery.kt

```kotlin
package com.sedationh.voicesync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 设备发现结果数据类
 */
data class DiscoveredDevice(
    val name: String,        // 设备名称，如 "VoiceSync-MacBook-Pro"
    val ipAddress: String,   // IP 地址，如 "192.168.1.100"
    val port: Int            // 端口号，如 4500
) {
    val address: String
        get() = "$ipAddress:$port"
}

/**
 * mDNS 服务发现管理器
 */
class ServiceDiscovery(private val context: Context) {
    
    companion object {
        private const val TAG = "ServiceDiscovery"
        private const val SERVICE_TYPE = "_voicesync._tcp."
    }
    
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }
    
    /**
     * 扫描局域网内的设备
     * @return Flow<List<DiscoveredDevice>> 发现的设备列表流
     */
    fun discoverDevices(): Flow<List<DiscoveredDevice>> = callbackFlow {
        val devices = mutableMapOf<String, DiscoveredDevice>()
        
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "启动发现失败: $errorCode")
                close()
            }
            
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "停止发现失败: $errorCode")
            }
            
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "开始扫描设备...")
            }
            
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "停止扫描")
            }
            
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "发现服务: ${serviceInfo.serviceName}")
                
                // 解析服务信息
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "解析失败: ${serviceInfo.serviceName}, 错误码: $errorCode")
                    }
                    
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val device = DiscoveredDevice(
                            name = serviceInfo.serviceName,
                            ipAddress = serviceInfo.host.hostAddress ?: "",
                            port = serviceInfo.port
                        )
                        
                        Log.d(TAG, "解析成功: ${device.name} @ ${device.address}")
                        
                        devices[device.name] = device
                        trySend(devices.values.toList())
                    }
                })
            }
            
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "设备离线: ${serviceInfo.serviceName}")
                devices.remove(serviceInfo.serviceName)
                trySend(devices.values.toList())
            }
        }
        
        // 开始扫描
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "启动扫描失败", e)
            close(e)
        }
        
        // 清理：停止扫描
        awaitClose {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.e(TAG, "停止扫描失败", e)
            }
        }
    }
}
```

#### 4.2.3 UI 组件：设备选择对话框

```kotlin
@Composable
fun DeviceDiscoveryDialog(
    onDismiss: () -> Unit,
    onDeviceSelected: (DiscoveredDevice) -> Unit
) {
    var devices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }
    
    val context = LocalContext.current
    val serviceDiscovery = remember { ServiceDiscovery(context) }
    
    LaunchedEffect(Unit) {
        serviceDiscovery.discoverDevices().collect { discoveredDevices ->
            devices = discoveredDevices
            isScanning = false
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫描局域网设备") },
        text = {
            if (isScanning && devices.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("正在扫描...")
                }
            } else if (devices.isEmpty()) {
                Text("未发现设备\n请确保 Mac 端已启动并连接同一 Wi-Fi")
            } else {
                LazyColumn {
                    items(devices) { device ->
                        ListItem(
                            headlineContent = { Text(device.name) },
                            supportingContent = { Text(device.address) },
                            modifier = Modifier.clickable {
                                onDeviceSelected(device)
                                onDismiss()
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
```

---

## 5. 接口设计

### 5.1 Mac 端 API

```swift
class ServiceBroadcaster {
    /// 开始广播
    func startBroadcast(port: Int, deviceName: String?)
    
    /// 停止广播
    func stopBroadcast()
    
    /// 发布状态
    @Published var isPublishing: Bool
    
    /// 最后的错误信息
    @Published var lastError: String?
}
```

### 5.2 Android 端 API

```kotlin
class ServiceDiscovery {
    /// 扫描设备（返回 Flow）
    fun discoverDevices(): Flow<List<DiscoveredDevice>>
}

data class DiscoveredDevice {
    val name: String        // 设备名称
    val ipAddress: String   // IP 地址
    val port: Int           // 端口号
    val address: String     // 完整地址 "IP:Port"
}
```

---

## 6. 用户体验

### 6.1 Android 端交互流程

```
┌─────────────┐
│  设置页面    │
└──────┬──────┘
       │
       │ 点击 "扫描局域网设备"
       ▼
┌─────────────┐
│  扫描对话框  │ ◄── 显示 "正在扫描..." + 转圈动画
└──────┬──────┘
       │
       │ 1-3秒后发现设备
       ▼
┌─────────────────────────┐
│  设备列表                │
│  ✓ VoiceSync-MacBook    │
│    192.168.1.100:4500   │ ◄── 点击选择
│  ✓ VoiceSync-iMac       │
│    192.168.1.101:4500   │
└──────┬──────────────────┘
       │
       │ 点击设备
       ▼
┌─────────────┐
│ 自动填充 IP  │
│ 并关闭对话框 │
└─────────────┘
```

### 6.2 降级方案

如果自动发现失败：
1. 显示提示："未发现设备，请手动输入 IP 地址"
2. 保留原有的手动输入功能
3. 提示检查：
   - Mac 端是否已启动
   - 是否连接同一 Wi-Fi
   - 防火墙是否屏蔽

---

## 7. 测试方案

### 7.1 功能测试

| 测试场景 | 预期结果 | 优先级 |
|---------|---------|--------|
| **同一 Wi-Fi** | Android 能发现 Mac | P0 |
| **多台 Mac** | 显示所有可用设备 | P0 |
| **Mac 离线** | 设备从列表中消失 | P1 |
| **Mac 重启** | 重新扫描可发现 | P1 |
| **不同 Wi-Fi** | 无法发现（预期行为）| P1 |
| **防火墙开启** | 测试是否影响发现 | P1 |

### 7.2 性能测试

- **扫描时间**：< 3 秒
- **解析时间**：< 1 秒/设备
- **内存占用**：< 5MB
- **电量消耗**：扫描期间可忽略

### 7.3 兼容性测试

| 平台 | 最低版本 | 测试设备 |
|------|---------|---------|
| **macOS** | 10.15+ | MacBook Pro, iMac |
| **Android** | 6.0+ (API 23+) | 各厂商设备 |

---

## 8. 风险与注意事项

### 8.1 技术风险

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| **路由器不支持 mDNS** | 无法发现设备 | 保留手动输入功能 |
| **防火墙屏蔽** | 广播失败 | 提供配置指引 |
| **多个同名设备** | 混淆 | 在名称中加入设备唯一标识 |
| **扫描超时** | 用户等待 | 设置 5 秒超时 |

### 8.2 安全考虑

- ✅ **局域网限制**：mDNS 仅在局域网内工作
- ✅ **无需认证**：同一 Wi-Fi 内的设备互信
- ⚠️ **公共 Wi-Fi**：建议提示用户风险（可选）

### 8.3 最佳实践

1. **设备命名**：使用有意义的名称，便于识别
2. **错误提示**：清晰告知用户失败原因
3. **定时刷新**：每 30 秒刷新一次设备列表（可选）
4. **缓存策略**：保存上次连接的设备，优先显示

---

## 9. 实施计划

### 9.1 开发阶段

| 阶段 | 任务 | 预计时间 |
|------|------|---------|
| **Phase 1** | Mac 端广播功能 | 1 小时 |
| **Phase 2** | Android 端扫描功能 | 1.5 小时 |
| **Phase 3** | UI 集成与测试 | 1 小时 |
| **Phase 4** | 文档与调优 | 0.5 小时 |

**总计**：约 4 小时

### 9.2 发布计划

1. **内部测试**：开发者自测（同一 Wi-Fi 环境）
2. **Beta 测试**：邀请用户测试（多种网络环境）
3. **正式发布**：随下一版本发布

---

## 10. 参考资料

### 10.1 官方文档

- [Apple - Bonjour Overview](https://developer.apple.com/bonjour/)
- [Apple - NetService](https://developer.apple.com/documentation/foundation/netservice)
- [Android - NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager)
- [Android - Network Service Discovery](https://developer.android.com/training/connect-devices-wirelessly/nsd)

### 10.2 技术文章

- [mDNS/DNS-SD 协议详解](https://datatracker.ietf.org/doc/html/rfc6762)
- [Zero Configuration Networking](https://en.wikipedia.org/wiki/Zero-configuration_networking)

---

## 附录：术语表

| 术语 | 全称 | 说明 |
|------|------|------|
| **mDNS** | Multicast DNS | 多播 DNS，用于局域网内的服务发现 |
| **Bonjour** | - | Apple 的 Zero Configuration 实现 |
| **NSD** | Network Service Discovery | Android 的网络服务发现 API |
| **DNS-SD** | DNS Service Discovery | 基于 DNS 的服务发现协议 |
| **NetService** | - | macOS/iOS 的 Bonjour 服务类 |

---

**文档状态**：✅ 已完成  
**下一步**：开始编码实施
