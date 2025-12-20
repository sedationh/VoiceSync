//
//  ContentView.swift
//  VoiceSyncMac
//
//  Created by seda on 2025/12/20.
//

import SwiftUI
import Swifter
import AppKit // 操作剪贴板必须引入
import Network // 获取本机 IP

// 获取本机局域网 IP 地址
func getLocalIPAddress() -> String? {
    var address: String?
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    
    guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return nil }
    defer { freeifaddrs(ifaddr) }
    
    for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
        let interface = ptr.pointee
        let addrFamily = interface.ifa_addr.pointee.sa_family
        
        // 只要 IPv4 地址，排除 127.0.0.1
        if addrFamily == UInt8(AF_INET) {
            let name = String(cString: interface.ifa_name)
            // en0 是 Wi-Fi，en1 可能是有线网
            if name == "en0" || name == "en1" {
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                if getnameinfo(interface.ifa_addr, socklen_t(interface.ifa_addr.pointee.sa_len),
                               &hostname, socklen_t(hostname.count), nil, 0, NI_NUMERICHOST) == 0 {
                    address = String(cString: hostname)
                }
            }
        }
    }
    return address
}

// 2.2 数据模型：每一条同步记录
struct SyncItem: Identifiable {
    let id = UUID()
    let content: String
    let timestamp: Date = Date()
}

// 核心管理类
class SyncManager: ObservableObject {
    @Published var history: [SyncItem] = []
    @Published var lastSyncTime: Date? = nil
    
    // 2.1 & 2.3 核心方法：更新剪贴板并记录历史
    func handleNewContent(_ text: String) {
        // 必须在主线程操作 UI 和系统服务
        DispatchQueue.main.async {
            // 1. 写入系统剪贴板
            let pasteboard = NSPasteboard.general
            pasteboard.declareTypes([.string], owner: nil)
            pasteboard.setString(text, forType: .string)
            
            // 2. 存入历史列表（插到最前面）
            let newItem = SyncItem(content: text)
            self.history.insert(newItem, at: 0)
            self.lastSyncTime = Date()
            
            // 限制历史记录数量，防止内存占用过大（可选）
            if self.history.count > 50 {
                self.history.removeLast()
            }
        }
    }
    
    // 清空历史记录
    func clearHistory() {
        history.removeAll()
    }
    
    // 删除单条记录
    func deleteItem(_ item: SyncItem) {
        history.removeAll { $0.id == item.id }
    }
    
    // 仅复制到剪贴板（不添加历史记录）
    func copyToClipboard(_ text: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.declareTypes([.string], owner: nil)
        pasteboard.setString(text, forType: .string)
    }
}

struct ContentView: View {
    private let server = HttpServer()
    @StateObject private var syncManager = SyncManager()
    @State private var statusText = "服务待启动..."
    @State private var showClearConfirm = false
    @State private var searchText = ""
    @State private var localIP: String = "获取中..."
    @State private var showCopiedTip = false

    var filteredHistory: [SyncItem] {
        if searchText.isEmpty {
            return syncManager.history
        }
        return syncManager.history.filter { $0.content.localizedCaseInsensitiveContains(searchText) }
    }
    
    var isRunning: Bool {
        statusText.contains("运行中")
    }
    
    // 完整的连接地址
    var fullAddress: String {
        "\(localIP):4500"
    }

    var body: some View {
        VStack(spacing: 0) {
            // 顶部状态栏 - 简洁设计
            HStack(spacing: 8) {
                Circle()
                    .fill(isRunning ? Color.green : Color.red)
                    .frame(width: 8, height: 8)
                Text(isRunning ? "运行中" : "已停止")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(isRunning ? .green : .red)
                
                Spacer()
                
                if !syncManager.history.isEmpty {
                    Text("\(syncManager.history.count)")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.accentColor.opacity(0.8))
                        .clipShape(Capsule())
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Color(NSColor.windowBackgroundColor))
            
            // IP 地址栏 - 点击复制
            Button(action: {
                syncManager.copyToClipboard(fullAddress)
                showCopiedTip = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                    showCopiedTip = false
                }
            }) {
                HStack(spacing: 6) {
                    Image(systemName: "network")
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                    
                    Text(fullAddress)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundColor(.primary)
                    
                    Spacer()
                    
                    if showCopiedTip {
                        Text("已复制!")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundColor(.green)
                            .transition(.opacity)
                    } else {
                        Image(systemName: "doc.on.doc")
                            .font(.system(size: 10))
                            .foregroundColor(.accentColor)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.accentColor.opacity(0.08))
            }
            .buttonStyle(PlainButtonStyle())
            .help("点击复制地址，发送到手机")
            
            Divider()
            
            // 主内容区
            if syncManager.history.isEmpty {
                // 空状态
                emptyStateView
            } else {
                // 搜索栏
                searchBar
                    .padding(.horizontal, 12)
                    .padding(.top, 12)
                    .padding(.bottom, 4)
                
                // 历史列表
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(filteredHistory) { item in
                            SyncItemCard(item: item, onCopy: {
                                syncManager.copyToClipboard(item.content)
                            }, onDelete: {
                                withAnimation(.easeOut(duration: 0.2)) {
                                    syncManager.deleteItem(item)
                                }
                            })
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                }
                
                if filteredHistory.isEmpty && !searchText.isEmpty {
                    Text("无匹配结果")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                
                Divider()
                
                // 底部栏
                bottomBar
            }
        }
        .frame(width: 340, height: 480)
        .background(Color(NSColor.controlBackgroundColor))
        .alert("确认清空", isPresented: $showClearConfirm) {
            Button("取消", role: .cancel) { }
            Button("清空", role: .destructive) {
                withAnimation { syncManager.clearHistory() }
            }
        } message: {
            Text("确定要清空所有历史记录吗？")
        }
        .onAppear {
            // 获取本机 IP
            localIP = getLocalIPAddress() ?? "未知"
            
            if ProcessInfo.processInfo.environment["XCODE_RUNNING_FOR_PREVIEWS"] == nil {
                setupServer()
            }
        }
    }
    
    // 空状态视图
    private var emptyStateView: some View {
        VStack(spacing: 16) {
            Spacer()
            
            ZStack {
                Circle()
                    .fill(Color.accentColor.opacity(0.1))
                    .frame(width: 80, height: 80)
                Image(systemName: "arrow.right.circle")
                    .font(.system(size: 36, weight: .light))
                    .foregroundColor(.accentColor)
            }
            
            VStack(spacing: 6) {
                Text("等待同步")
                    .font(.headline)
                    .foregroundColor(.primary)
                Text("从 Android 设备发送内容")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
            }
            
            Spacer()
        }
    }
    
    // 搜索栏
    private var searchBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 12))
                .foregroundColor(.secondary)
            
            TextField("搜索...", text: $searchText)
                .textFieldStyle(PlainTextFieldStyle())
                .font(.system(size: 13))
            
            if !searchText.isEmpty {
                Button(action: { searchText = "" }) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 12))
                        .foregroundColor(.secondary)
                }
                .buttonStyle(PlainButtonStyle())
            }
        }
        .padding(8)
        .background(Color(NSColor.textBackgroundColor))
        .cornerRadius(8)
    }
    
    // 底部栏
    private var bottomBar: some View {
        HStack {
            if let lastTime = syncManager.lastSyncTime {
                Image(systemName: "clock")
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
                Text(lastTime, style: .relative)
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
            }
            
            Spacer()
            
            Button(action: { showClearConfirm = true }) {
                Image(systemName: "trash")
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
            }
            .buttonStyle(PlainButtonStyle())
            .help("清空所有记录")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color(NSColor.windowBackgroundColor))
    }

    func setupServer() {
        server["/sync"] = { request in
            let body = String(bytes: request.body, encoding: .utf8) ?? ""
            
            if !body.isEmpty {
                print("收到内容并注入剪贴板: \(body)")
                syncManager.handleNewContent(body)
                return .ok(.text("Success"))
            }
            return .badRequest(nil)
        }

        do {
            try server.start(4500)
            statusText = "服务运行中"
        } catch {
            statusText = "启动失败: \(error)"
        }
    }
}

// 卡片式记录组件
struct SyncItemCard: View {
    let item: SyncItem
    let onCopy: () -> Void
    let onDelete: () -> Void
    @State private var isHovered = false
    
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(item.content)
                .font(.system(size: 13))
                .foregroundColor(.primary)
                .lineLimit(3)
                .frame(maxWidth: .infinity, alignment: .leading)
            
            HStack {
                Text(item.timestamp, style: .time)
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
                
                Spacer()
                
                if isHovered {
                    HStack(spacing: 12) {
                        Button(action: onCopy) {
                            Image(systemName: "doc.on.doc")
                                .font(.system(size: 11))
                                .foregroundColor(.accentColor)
                        }
                        .buttonStyle(PlainButtonStyle())
                        .help("复制")
                        
                        Button(action: onDelete) {
                            Image(systemName: "trash")
                                .font(.system(size: 11))
                                .foregroundColor(.red.opacity(0.8))
                        }
                        .buttonStyle(PlainButtonStyle())
                        .help("删除")
                    }
                    .transition(.opacity.combined(with: .scale(scale: 0.8)))
                }
            }
        }
        .padding(12)
        .background(Color(NSColor.textBackgroundColor))
        .cornerRadius(10)
        .shadow(color: .black.opacity(0.04), radius: 2, x: 0, y: 1)
        .contentShape(Rectangle())
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.15)) {
                isHovered = hovering
            }
        }
        .contextMenu {
            Button("复制") { onCopy() }
            Divider()
            Button("删除", role: .destructive) { onDelete() }
        }
    }
}

#Preview {
    ContentView()
}
