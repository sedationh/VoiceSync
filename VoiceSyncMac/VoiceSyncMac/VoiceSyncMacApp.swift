//
//  VoiceSyncMacApp.swift
//  VoiceSyncMac
//
//  Created by seda on 2025/12/20.
//

import SwiftUI
import Swifter
import Combine

// App 级别的服务管理器
class AppState: ObservableObject {
    static let shared = AppState()
    
    let server = HttpServer()
    var syncManager = SyncManager()
    
    @Published var isRunning = false
    @Published var localIP: String = "获取中..."
    @Published var hasNewMessage = false
    
    private var cancellables = Set<AnyCancellable>()
    
    var fullAddress: String {
        "\(localIP):4500"
    }
    
    private init() {
        localIP = getLocalIPAddress() ?? "未知"
        
        // 转发 syncManager 的变化通知
        syncManager.objectWillChange
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.objectWillChange.send()
            }
            .store(in: &cancellables)
        
        startServer()
    }
    
    func startServer() {
        server["/sync"] = { [weak self] request in
            let body = String(bytes: request.body, encoding: .utf8) ?? ""
            
            if !body.isEmpty {
                print("收到内容并注入剪贴板: \(body)")
                DispatchQueue.main.async {
                    self?.syncManager.handleNewContent(body)
                    self?.markNewMessage()
                }
                return .ok(.text("Success"))
            }
            return .badRequest(nil)
        }

        do {
            try server.start(4500)
            isRunning = true
        } catch {
            isRunning = false
            print("启动失败: \(error)")
        }
    }
    
    // 标记有新消息
    func markNewMessage() {
        hasNewMessage = true
        // 3秒后自动清除新消息标记
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
            self?.hasNewMessage = false
        }
    }
}

@main
struct VoiceSyncMacApp: App {
    @StateObject private var appState = AppState.shared
    
    var body: some Scene {
        // 主窗口 - 使用 Window 保证单窗口
        Window("VoiceSync", id: "main") {
            ContentView()
                .environmentObject(appState)
        }
        .defaultSize(width: 340, height: 480)
        
        // MenuBar 图标
        MenuBarExtra {
            MenuBarView(openMainWindow: {
                // 先激活应用
                NSApplication.shared.activate(ignoringOtherApps: true)
                
                // 查找并显示主窗口
                for window in NSApplication.shared.windows {
                    // 排除菜单栏弹出窗口和其他系统窗口
                    if window.title == "VoiceSync" || window.identifier?.rawValue == "main" {
                        window.makeKeyAndOrderFront(nil)
                        window.orderFrontRegardless()
                        return
                    }
                }
                
                // 如果窗口不存在或被关闭，尝试通过所有窗口找到内容窗口
                if let window = NSApplication.shared.windows.first(where: { 
                    $0.contentView != nil && 
                    !($0.className.contains("MenuBarExtra")) &&
                    $0.level == .normal
                }) {
                    window.makeKeyAndOrderFront(nil)
                    window.orderFrontRegardless()
                }
            })
            .environmentObject(appState)
        } label: {
            // 有新消息时图标变绿色
            if appState.hasNewMessage {
                Label {
                    Text("")
                } icon: {
                    Image(systemName: "waveform.circle.fill")
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.green, .green)
                }
            } else {
                Image(systemName: "waveform.circle.fill")
            }
        }
        .menuBarExtraStyle(.window)
    }
}

// MenuBar 弹出视图 - 重新设计
struct MenuBarView: View {
    @EnvironmentObject var appState: AppState
    @State private var showCopiedTip = false
    @State private var showContentCopiedTip = false
    let openMainWindow: () -> Void
    
    var body: some View {
        VStack(spacing: 0) {
            // 头部：状态 + 打开主窗口
            headerSection
            
            Divider().opacity(0.5)
            
            // 自动粘贴开关
            autoPasteSection
            
            Divider().opacity(0.5)
            
            // IP 地址区
            addressSection
            
            Divider().opacity(0.5)
            
            // 最近同步内容
            recentSyncSection
            
            Divider().opacity(0.5)
            
            // 底部操作
            footerSection
        }
        .frame(width: 300)
        .background(.regularMaterial)
    }
    
    // MARK: - 自动粘贴开关
    private var autoPasteSection: some View {
        HStack(spacing: 10) {
            Image(systemName: "doc.on.clipboard")
                .font(.system(size: 14))
                .foregroundStyle(.secondary)
                .frame(width: 24)
            
            VStack(alignment: .leading, spacing: 2) {
                Text("自动粘贴")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.primary)
                Text("同步后自动输入到光标位置")
                    .font(.system(size: 10))
                    .foregroundStyle(.tertiary)
            }
            
            Spacer()
            
            Toggle("", isOn: $appState.syncManager.autoPasteEnabled)
                .toggleStyle(.switch)
                .controlSize(.small)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }
    
    // MARK: - 头部
    private var headerSection: some View {
        HStack {
            // 状态指示
            HStack(spacing: 6) {
                Circle()
                    .fill(appState.isRunning ? Color.green : Color.red)
                    .frame(width: 8, height: 8)
                    .shadow(color: appState.isRunning ? .green.opacity(0.5) : .clear, radius: 4)
                
                Text(appState.isRunning ? "服务运行中" : "服务已停止")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(.primary)
            }
            
            Spacer()
            
            // 记录数量
            if !appState.syncManager.history.isEmpty {
                Text("\(appState.syncManager.history.count) 条")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(.quaternary)
                    .clipShape(Capsule())
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
    
    // MARK: - 地址区
    private var addressSection: some View {
        Button(action: copyAddress) {
            HStack(spacing: 10) {
                Image(systemName: "network")
                    .font(.system(size: 14))
                    .foregroundStyle(.secondary)
                    .frame(width: 24)
                
                VStack(alignment: .leading, spacing: 2) {
                    Text("连接地址")
                        .font(.system(size: 10))
                        .foregroundStyle(.tertiary)
                    Text(appState.fullAddress)
                        .font(.system(size: 13, weight: .medium, design: .monospaced))
                        .foregroundStyle(.primary)
                }
                
                Spacer()
                
                if showCopiedTip {
                    Label("已复制", systemImage: "checkmark.circle.fill")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.green)
                } else {
                    Image(systemName: "doc.on.doc")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(MenuItemButtonStyle())
    }
    
    // MARK: - 最近同步
    private var recentSyncSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("最近同步")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.secondary)
                Spacer()
            }
            
            if let lastItem = appState.syncManager.history.first {
                Button(action: copyLastContent) {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "text.bubble")
                            .font(.system(size: 14))
                            .foregroundStyle(.secondary)
                            .frame(width: 24)
                        
                        VStack(alignment: .leading, spacing: 4) {
                            Text(lastItem.content)
                                .font(.system(size: 12))
                                .foregroundStyle(.primary)
                                .lineLimit(3)
                                .multilineTextAlignment(.leading)
                            
                            Text(lastItem.timestamp, style: .relative)
                                .font(.system(size: 10))
                                .foregroundStyle(.tertiary)
                        }
                        
                        Spacer()
                        
                        if showContentCopiedTip {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.system(size: 12))
                                .foregroundStyle(.green)
                        } else {
                            Image(systemName: "doc.on.doc")
                                .font(.system(size: 11))
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .padding(10)
                    .background(.quaternary.opacity(0.5))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .contentShape(Rectangle())
                }
                .buttonStyle(PlainButtonStyle())
            } else {
                HStack(spacing: 10) {
                    Image(systemName: "tray")
                        .font(.system(size: 14))
                        .foregroundStyle(.tertiary)
                        .frame(width: 24)
                    
                    Text("暂无同步记录")
                        .font(.system(size: 12))
                        .foregroundStyle(.tertiary)
                    
                    Spacer()
                }
                .padding(10)
                .background(.quaternary.opacity(0.3))
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
    
    // MARK: - 底部
    private var footerSection: some View {
        HStack(spacing: 12) {
            Button(action: openMainWindow) {
                Label("打开主窗口", systemImage: "macwindow")
                    .font(.system(size: 12))
            }
            .buttonStyle(MenuFooterButtonStyle())
            
            Spacer()
            
            Button(action: { NSApplication.shared.terminate(nil) }) {
                Label("退出", systemImage: "power")
                    .font(.system(size: 12))
                    .foregroundStyle(.red)
            }
            .buttonStyle(MenuFooterButtonStyle())
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }
    
    // MARK: - Actions
    private func copyAddress() {
        appState.syncManager.copyToClipboard(appState.fullAddress)
        withAnimation(.easeInOut(duration: 0.2)) {
            showCopiedTip = true
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            withAnimation { showCopiedTip = false }
        }
    }
    
    private func copyLastContent() {
        if let content = appState.syncManager.history.first?.content {
            appState.syncManager.copyToClipboard(content)
            withAnimation(.easeInOut(duration: 0.2)) {
                showContentCopiedTip = true
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                withAnimation { showContentCopiedTip = false }
            }
        }
    }
}

// MARK: - 自定义按钮样式
struct MenuItemButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color.primary.opacity(0.1) : Color.clear)
    }
}

struct MenuFooterButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(configuration.isPressed ? Color.primary.opacity(0.1) : Color.primary.opacity(0.05))
            .clipShape(RoundedRectangle(cornerRadius: 6))
    }
}
