//
//  ContentView.swift
//  VoiceSyncMac
//
//  Created by seda on 2025/12/20.
//

import SwiftUI
import Swifter
import AppKit // 操作剪贴板必须引入

// 2.2 数据模型：每一条同步记录
struct SyncItem: Identifiable {
    let id = UUID()
    let content: String
    let timestamp: Date = Date()
}

// 核心管理类
class SyncManager: ObservableObject {
    @Published var history: [SyncItem] = []
    
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
            
            // 限制历史记录数量，防止内存占用过大（可选）
            if self.history.count > 50 {
                self.history.removeLast()
            }
        }
    }
}

struct ContentView: View {
    private let server = HttpServer()
    @StateObject private var syncManager = SyncManager() // 观察管理类
    @State private var statusText = "服务待启动..."

    var body: some View {
        VStack(spacing: 0) {
            // 顶部状态栏
            HStack {
                Circle()
                    .fill(statusText.contains("运行中") ? Color.green : Color.red)
                    .frame(width: 8, height: 8)
                Text(statusText)
                    .font(.subheadline)
                Spacer()
                Text("端口: 4500").font(.caption).foregroundColor(.secondary)
            }
            .padding()
            .background(Color.gray.opacity(0.05))

            // 2.4 历史记录列表
            List(syncManager.history) { item in
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.content)
                        .lineLimit(2)
                        .font(.body)
                    Text(item.timestamp, style: .time)
                        .font(.system(size: 10))
                        .foregroundColor(.secondary)
                }
                .padding(.vertical, 4)
                .contextMenu {
                    Button("重新复制") {
                        syncManager.handleNewContent(item.content)
                    }
                }
            }
            .listStyle(InsetListStyle())
            
            if syncManager.history.isEmpty {
                VStack {
                    Spacer()
                    Text("等待 Android 端同步...").foregroundColor(.secondary)
                    Spacer()
                }
            }
        }
        .frame(width: 350, height: 500)
        .onAppear {
            if ProcessInfo.processInfo.environment["XCODE_RUNNING_FOR_PREVIEWS"] == nil {
                setupServer()
            }
        }
    }

    func setupServer() {
        // 2.3 完善路由逻辑
        server["/sync"] = { request in
            let body = String(bytes: request.body, encoding: .utf8) ?? ""
            
            if !body.isEmpty {
                print("收到内容并注入剪贴板: \(body)")
                // 调用管理类处理内容
                syncManager.handleNewContent(body)
                return .ok(.text("Success"))
            }
            return .badRequest(nil)
        }

        do {
            try server.start(4500)
            statusText = "服务运行中 (Port 4500)"
        } catch {
            statusText = "启动失败: \(error)"
        }
    }
}

#Preview {
    ContentView()
}
