# 📅 VoiceSync 开发进度执行表 (Plan.md)

## 🏗 Phase 1: 基础设施与网络打通 (MVP)

> 目标：实现 Android 到 Mac 的最基本字符串传输。

* [ ] **1.1 [Mac] 环境初始化**
* 创建 macOS SwiftUI 项目 `VoiceSyncMac`。
* 在 `Signing & Capabilities` 中开启 `App Sandbox` -> `Incoming Connections (Server)` 权限。

* [ ] **1.2 [Mac] 集成 HTTP 服务库**
* 通过 SPM (Swift Package Manager) 引入 `Swifter` 或 `FlyingFox` 库。

* [ ] **1.3 [Mac] 编写基础接收逻辑**
* 启动监听 3000 端口。
* 编写 `POST /sync` 接口，解析 Body 并在控制台 `print` 接收到的内容。

* [ ] **1.4 [Android] 极简发送端**
* 创建 Android Compose 项目。
* 赋予 `INTERNET` 权限。
* 放置一个 `TextField`（输入 IP）和一个 `Button`（点击后发送固定字符串 "Hello Mac"）。

* [ ] **1.5 [验证] 联通测试**
* 手机点击按钮，Mac 控制台成功打印出字符串。

---

## 🍎 Phase 2: Mac 端核心功能 (剪贴板注入)

> 目标：让 Mac 真正动起来，实现自动粘贴和历史预览。

* [ ] **2.1 [Mac] 剪贴板工具类**
* 封装 `NSPasteboard` 写入函数。

* [ ] **2.2 [Mac] 数据模型与状态管理**
* 定义 `SyncItem` 模型（含时间戳和内容）。
* 创建 `SyncManager` 观察者对象，管理历史记录列表。

* [ ] **2.3 [Mac] 完善路由处理**
* 修改 `/sync` 接口：接收到数据后，触发剪贴板写入，并将其插入 `SyncManager` 的列表顶部。
* 注意：确保在 `DispatchQueue.main` 中执行这些操作。

* [ ] **2.4 [Mac] 历史记录 UI**
* 使用 SwiftUI `List` 展示历史记录。
* 界面显著位置显示本机当前 IP（方便安卓端配置）。

* [ ] **2.5 [验证] 完整链路测试**
* 安卓发送一段文字，Mac 列表更新，并在第三方软件（如备忘录）中成功 `Cmd+V` 粘贴。

---

## 🤖 Phase 3: Android 端核心功能 (智能自动化)

> 目标：实现“输入即同步”的防抖逻辑。

* [ ] **3.1 [Android] 配置持久化**
* 使用 `DataStore` 或 `SharedPreferences` 保存 Mac 的 IP 地址，下次启动免输入。

* [ ] **3.2 [Android] 自动发送防抖逻辑 (Debounce)**
* 实现输入监听，使用协程 `delay` 设置 2s 倒计时。
* 确保新输入能立即重置旧的计时器。

* [ ] **3.3 [Android] 网络请求封装**
* 将发送逻辑封装为 `POST` 请求， Body 符合 JSON 规范。

* [ ] **3.4 [Android] 交互反馈**
* 发送中、发送成功、发送失败的 UI 状态提示（如颜色变化或小图标）。

* [ ] **3.5 [验证] 自动化测试**
* 手机停顿说话 2 秒，Mac 自动同步。

---

## ✨ Phase 4: 体验增强与自动清除

> 目标：追求极致的自动化体验，完成闭环。

* [ ] **4.1 [Android] 自动清除逻辑**
* 在发送成功回调后，启动 3s “清除计时器”。
* 若 3s 内无新操作，调用 `text = ""` 清空工作区。

* [ ] **4.2 [Android] 自动清除开关**
* 在设置页面添加“自动清除”开关及延迟时间调节。

* [ ] **4.3 [Mac] 体验打磨**
* 添加“清空所有记录”按钮。
* 支持点击历史记录重新复制。

* [ ] **4.4 [最终测试] 全链路回归**
* 模拟真实语音输入场景：说话 -> 自动同步 -> 等待 3 秒 -> 自动清空 -> 下一轮。

---

## 🚀 进阶与优化 (P2 - 计划外)

* [ ] [Mac/Android] 集成 mDNS (Bonjour) 实现自动发现设备。
* [ ] [Mac] 实现 MenuBar 模式，隐藏主窗口运行。
