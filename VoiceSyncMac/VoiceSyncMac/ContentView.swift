//
//  ContentView.swift
//  VoiceSyncMac
//
//  Created by seda on 2025/12/20.
//

import SwiftUI
import Swifter

struct ContentView: View {
    // 创建服务器实例
    private let server = HttpServer()
    @State private var statusText = "服务待启动..."
    @State private var lastMessage = "暂无消息"

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.system(size: 50))
                .foregroundColor(.blue)
            
            Text("VoiceSync 控制台")
                .font(.title2)
            
            Divider()
            
            VStack(alignment: .leading, spacing: 10) {
                Text("状态：\(statusText)")
                    .fontWeight(.bold)
                
                Text("最后收到的内容：")
                    .font(.caption)
                    .foregroundColor(.secondary)
                
                Text(lastMessage)
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.gray.opacity(0.1))
                    .cornerRadius(8)
            }
            
            Text("监听端口: 4500")
                .font(.footnote)
                .foregroundColor(.gray)
        }
        .padding()
        .frame(width: 350, height: 400)
        .onAppear {
            // 只有在非预览模式下才启动服务器
            if ProcessInfo.processInfo.environment["XCODE_RUNNING_FOR_PREVIEWS"] == nil {
                setupServer()
            }
        }
    }

    func setupServer() {
        // 定义接收数据的路径
        server["/sync"] = { request in
            // 解析 Body 数据
            let body = String(bytes: request.body, encoding: .utf8) ?? "解析失败"
            print("收到请求: \(body)")
            
            // 必须在主线程更新 UI
            DispatchQueue.main.async {
                self.lastMessage = body
                self.statusText = "已接收数据 ✅"
            }
            
            return .ok(.text("Mac 已收到！"))
        }

        do {
            // 启动服务器
            try server.start(4500)
            statusText = "服务运行中 (Port 4500)..."
        } catch {
            statusText = "启动失败: \(error)"
        }
    }
}

#Preview {
    ContentView()
}
