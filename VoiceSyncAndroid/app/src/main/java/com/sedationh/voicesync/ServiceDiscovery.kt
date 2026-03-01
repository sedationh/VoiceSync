package com.sedationh.voicesync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 发现的设备数据类
 * 
 * @property name 设备名称，如 "VoiceSync-MacBook-Pro"
 * @property ipAddress IP 地址，如 "192.168.1.100"
 * @property port 端口号，如 4500
 */
data class DiscoveredDevice(
    val name: String,
    val ipAddress: String,
    val port: Int
) {
    /**
     * 完整地址，格式为 "IP:Port"
     */
    val address: String
        get() = "$ipAddress:$port"
    
    /**
     * 显示名称（移除 VoiceSync- 前缀，更简洁）
     */
    val displayName: String
        get() = name.removePrefix("VoiceSync-")
}

/**
 * mDNS/NSD 服务发现管理器
 * 
 * 功能：
 * - 扫描局域网内的 VoiceSync Mac 设备
 * - 自动解析设备的 IP 地址和端口
 * - 实时更新设备列表（设备上线/离线）
 * 
 * 使用方法：
 * ```kotlin
 * val serviceDiscovery = ServiceDiscovery(context)
 * serviceDiscovery.discoverDevices().collect { devices ->
 *     // 处理发现的设备列表
 * }
 * ```
 */
class ServiceDiscovery(private val context: Context) {
    
    companion object {
        private const val TAG = "ServiceDiscovery"
        
        /**
         * 服务类型标识
         * - _voicesync: 自定义服务名称
         * - _tcp: TCP 协议（HTTP 基于 TCP）
         * - . : 本地域名后缀
         */
        private const val SERVICE_TYPE = "_voicesync._tcp."
    }
    
    /**
     * NsdManager 实例，懒加载
     */
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }
    
    /**
     * 扫描局域网内的 VoiceSync 设备
     * 
     * @return Flow<List<DiscoveredDevice>> 设备列表流，实时更新
     * 
     * 工作流程：
     * 1. 启动服务发现
     * 2. 当发现服务时，解析其 IP 和端口
     * 3. 将设备添加到列表中
     * 4. 当服务离线时，从列表中移除
     * 5. 停止扫描时，清理资源
     */
    fun discoverDevices(): Flow<List<DiscoveredDevice>> = callbackFlow {
        // 使用 Map 存储设备，key 为设备名称，避免重复
        val devices = mutableMapOf<String, DiscoveredDevice>()
        
        // 服务发现监听器
        val discoveryListener = object : NsdManager.DiscoveryListener {
            
            /**
             * 启动发现失败
             */
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "❌ 启动发现失败: errorCode=$errorCode, serviceType=$serviceType")
                close(Exception("启动服务发现失败，错误码: $errorCode"))
            }
            
            /**
             * 停止发现失败
             */
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "❌ 停止发现失败: errorCode=$errorCode")
            }
            
            /**
             * 发现已启动
             */
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "🔍 开始扫描设备... (serviceType=$serviceType)")
            }
            
            /**
             * 发现已停止
             */
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "⏹️ 停止扫描 (serviceType=$serviceType)")
            }
            
            /**
             * 发现新服务
             */
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "👀 发现服务: ${serviceInfo.serviceName}")
                
                // 解析服务信息以获取 IP 和端口
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    
                    /**
                     * 解析失败
                     */
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "❌ 解析失败: ${serviceInfo.serviceName}, errorCode=$errorCode")
                        // 常见错误:
                        // -1: FAILURE
                        // -2: ALREADY_ACTIVE (已经在解析，可以忽略)
                        // -3: MAX_LIMIT (同时解析数量限制)
                        if (errorCode != NsdManager.FAILURE_ALREADY_ACTIVE) {
                            Log.w(TAG, "   💡 提示: 可能需要稍后重试")
                        }
                    }
                    
                    /**
                     * 解析成功
                     */
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        // 提取设备信息
                        val device = DiscoveredDevice(
                            name = serviceInfo.serviceName,
                            ipAddress = serviceInfo.host?.hostAddress ?: "",
                            port = serviceInfo.port
                        )
                        
                        // IP 地址为空，跳过
                        if (device.ipAddress.isEmpty()) {
                            Log.w(TAG, "⚠️ 设备 ${device.name} 的 IP 地址为空，跳过")
                            return
                        }
                        
                        Log.d(TAG, "✅ 解析成功: ${device.name} @ ${device.address}")
                        
                        // 添加到设备列表
                        devices[device.name] = device
                        
                        // 发送更新后的设备列表
                        trySend(devices.values.toList())
                    }
                })
            }
            
            /**
             * 服务丢失（设备离线）
             */
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "📴 设备离线: ${serviceInfo.serviceName}")
                
                // 从列表中移除
                devices.remove(serviceInfo.serviceName)
                
                // 发送更新后的设备列表
                trySend(devices.values.toList())
            }
        }
        
        // 开始扫描
        try {
            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
            Log.i(TAG, "🚀 服务发现已启动")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 启动扫描失败", e)
            close(e)
        }
        
        // 使用 awaitClose 确保在 Flow 被取消时停止扫描
        awaitClose {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
                Log.i(TAG, "🛑 服务发现已停止")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 停止扫描失败", e)
            }
        }
    }
}
