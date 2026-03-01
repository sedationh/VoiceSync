//
//  ServiceBroadcaster.swift
//  VoiceSyncMac
//
//  Created by AI Assistant on 2026/03/01.
//  Bonjour 服务广播管理器 - 通过 mDNS 广播服务供局域网设备发现
//

import Foundation

/// Bonjour 服务广播管理器
/// 负责在局域网中广播 VoiceSync 服务，使 Android 设备能够自动发现 Mac
class ServiceBroadcaster: NSObject, ObservableObject {
    
    // MARK: - Properties
    
    /// NetService 实例，用于广播服务
    private var service: NetService?
    
    /// 是否正在广播
    @Published var isPublishing = false
    
    /// 最后的错误信息
    @Published var lastError: String?
    
    /// 当前广播的服务名称
    @Published var serviceName: String?
    
    // MARK: - Public Methods
    
    /// 开始广播服务
    /// - Parameters:
    ///   - port: HTTP 服务监听的端口号（如 4500）
    ///   - deviceName: 自定义设备名称，如果为 nil 则使用系统主机名
    func startBroadcast(port: Int, deviceName: String? = nil) {
        // 先停止已有的广播服务
        stopBroadcast()
        
        // 生成服务名称
        // 优先使用自定义名称，否则使用 "VoiceSync-{系统主机名}"
        let name: String
        if let customName = deviceName {
            name = customName
        } else {
            let hostName = Host.current().localizedName ?? "Mac"
            name = "VoiceSync-\(hostName)"
        }
        
        serviceName = name
        
        // 创建 NetService 实例
        // domain: "local." 表示本地域
        // type: "_voicesync._tcp." 是自定义的服务类型标识
        // name: 服务名称，用于在设备列表中显示
        // port: HTTP 服务的端口号
        service = NetService(
            domain: "local.",
            type: "_voicesync._tcp.",
            name: name,
            port: Int32(port)
        )
        
        service?.delegate = self
        
        // 开始发布服务
        service?.publish()
        
        print("📡 [ServiceBroadcaster] 开始广播服务: \(name) 在端口 \(port)")
    }
    
    /// 停止广播服务
    func stopBroadcast() {
        guard let service = service else { return }
        
        service.stop()
        self.service = nil
        isPublishing = false
        serviceName = nil
        lastError = nil
        
        print("🛑 [ServiceBroadcaster] 停止广播服务")
    }
    
    /// 重新广播（当网络状态改变时使用）
    func restartBroadcast(port: Int, deviceName: String? = nil) {
        print("🔄 [ServiceBroadcaster] 重新广播服务")
        startBroadcast(port: port, deviceName: deviceName)
    }
    
    // MARK: - Deinitialization
    
    deinit {
        stopBroadcast()
    }
}

// MARK: - NetServiceDelegate

extension ServiceBroadcaster: NetServiceDelegate {
    
    /// 服务发布成功的回调
    func netServiceDidPublish(_ sender: NetService) {
        DispatchQueue.main.async {
            self.isPublishing = true
            self.lastError = nil
            print("✅ [ServiceBroadcaster] 服务广播成功: \(sender.name)")
            print("   - 域名: \(sender.domain)")
            print("   - 类型: \(sender.type)")
            print("   - 端口: \(sender.port)")
        }
    }
    
    /// 服务发布失败的回调
    func netService(_ sender: NetService, didNotPublish errorDict: [String : NSNumber]) {
        DispatchQueue.main.async {
            self.isPublishing = false
            
            // 解析错误代码
            let errorCode = errorDict[NetService.errorCode] ?? NSNumber(value: -1)
            let errorDomain = errorDict[NetService.errorDomain] ?? NSNumber(value: -1)
            
            let errorMessage = "广播失败 - 错误码: \(errorCode), 域: \(errorDomain)"
            self.lastError = errorMessage
            
            print("❌ [ServiceBroadcaster] \(errorMessage)")
            print("   - 错误详情: \(errorDict)")
            
            // 常见错误提示
            if errorCode.intValue == -72000 {
                print("   💡 提示: 端口可能已被占用，请检查是否有其他 VoiceSync 实例在运行")
            }
        }
    }
    
    /// 服务停止的回调
    func netServiceDidStop(_ sender: NetService) {
        DispatchQueue.main.async {
            self.isPublishing = false
            print("⏹️ [ServiceBroadcaster] 服务已停止: \(sender.name)")
        }
    }
    
    /// 即将发布服务的回调（可选）
    func netServiceWillPublish(_ sender: NetService) {
        print("⏳ [ServiceBroadcaster] 准备发布服务: \(sender.name)")
    }
    
    /// 即将解析服务的回调（可选）
    func netServiceWillResolve(_ sender: NetService) {
        print("🔍 [ServiceBroadcaster] 准备解析服务: \(sender.name)")
    }
    
    /// 解析服务成功的回调（可选）
    func netServiceDidResolveAddress(_ sender: NetService) {
        print("✅ [ServiceBroadcaster] 服务解析成功: \(sender.name)")
    }
    
    /// 解析服务失败的回调（可选）
    func netService(_ sender: NetService, didNotResolve errorDict: [String : NSNumber]) {
        print("❌ [ServiceBroadcaster] 服务解析失败: \(errorDict)")
    }
}
