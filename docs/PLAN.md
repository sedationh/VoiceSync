# 📅 VoiceSync 开发进度执行表 (Plan.md)

## 🏗 Phase 1: 基础设施与网络打通 (MVP)

> 目标：实现 Android 到 Mac 的最基本字符串传输。

* [x] **1.1 [Mac] 环境初始化**
* 创建 macOS SwiftUI 项目 `VoiceSyncMac`。
* 在 `Signing & Capabilities` 中开启 `App Sandbox` -> `Incoming Connections (Server)` 权限。

* [x] **1.2 [Mac] 集成 HTTP 服务库**
* 通过 SPM (Swift Package Manager) 引入 `Swifter` 或 `FlyingFox` 库。

* [x] **1.3 [Mac] 编写基础接收逻辑**
* 启动监听 4500 端口。
* 编写 `POST /sync` 接口，解析 Body 并在控制台 `print` 接收到的内容。

* [x] **1.4 [Android] 极简发送端**
* 创建 Android Compose 项目。
* 赋予 `INTERNET` 权限。
* 放置一个 `TextField`（输入 IP）和一个 `Button`（点击后发送固定字符串 "Hello Mac"）。

* [x] **1.5 [验证] 联通测试**
* 手机点击按钮，Mac 控制台成功打印出字符串。

---

## 🍎 Phase 2: Mac 端核心功能 (剪贴板注入)

> 目标：让 Mac 真正动起来，实现自动粘贴和历史预览。

* [x] **2.1 [Mac] 剪贴板工具类**
* [x] **2.2 [Mac] 数据模型与状态管理**
* [x] **2.3 [Mac] 完善路由处理**
* [x] **2.4 [Mac] 历史记录 UI**
* [x] **2.5 [验证] 完整链路测试**

---

## 🤖 Phase 3: Android 端核心功能 (智能自动化)

> 目标：实现“输入即同步”的防抖逻辑。

* [x] **3.1 [Android] 配置持久化**
* [x] **3.2 [Android] 自动发送防抖逻辑 (Debounce)**
* [x] **3.3 [Android] 网络请求封装**
* [x] **3.4 [Android] 交互反馈**
* [x] **3.5 [验证] 自动化测试**

---

## ✨ Phase 4: 体验增强与自动清除

> 目标：追求极致的自动化体验，完成闭环。

* [x] **4.1 [Android] 自动清除逻辑**
* [x] **4.2 [Android] 自动清除开关**
* [x] **4.3 [Mac] 体验打磨**
* [x] **4.4 [最终测试] 全链路回归**

---

## 🚀 进阶与优化 (P2 - 计划外)

* [ ] [Mac/Android] 集成 mDNS (Bonjour) 实现自动发现设备。
* [x] [Mac] 实现 MenuBar 模式，隐藏主窗口运行。
