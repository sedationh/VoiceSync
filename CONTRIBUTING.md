# VoiceSync 贡献指南

这份文档给后续参与 VoiceSync 的开发者看：怎么搭环境、怎么改代码、怎么提交、怎么测试，以及怎么发布新版本。

## 项目结构

- `VoiceSyncAndroid/`：Android 端，Kotlin + Jetpack Compose，负责语音输入、图片选择、设备发现和发送 `/sync` 请求。
- `VoiceSyncMac/`：macOS 接收端，SwiftUI，负责 HTTP 接收、Bonjour/mDNS 广播、剪贴板写入和自动粘贴。
- `VoiceSyncWindows/`：Windows 接收端，Python + tkinter + Win32 API，负责 HTTP 接收、mDNS 广播、剪贴板写入、托盘和自动粘贴。
- `.github/workflows/release.yml`：tag 驱动的自动发布流水线。
- `build.sh`：本地构建入口。
- `release.sh`：手动上传本地 `dist/` 产物的备用发布脚本；正常发布优先使用 tag 触发 GitHub Actions。

## 开发环境

至少准备这些工具：

- Git 和 GitHub CLI：`gh auth login`
- Android：JDK 17、Android Studio 或 Android SDK
- macOS：Xcode
- Windows：Python 3.8+，用于运行和打包 `VoiceSyncWindows`

常用命令：

```bash
./build.sh help
./build.sh clean
./build.sh android
./build.sh android-dev
./build.sh mac
./build.sh windows
```

注意：`./build.sh all` 当前只构建 Mac 和 Android，不包含 Windows。Windows 构建需要在有 Python 和 PyInstaller 的环境里单独执行 `./build.sh windows`。

## 开发流程

1. 从最新 `main` 开始：

   ```bash
   git checkout main
   git pull --ff-only
   git checkout -b feat/short-description
   ```

2. 保持改动聚焦：一个 PR 只解决一个功能、修复或文档主题。
3. 提交前先跑和改动相关的检查。
4. commit 信息建议使用简短的约定式前缀：

   ```text
   feat: add windows receiver
   fix: avoid advertising unavailable service
   docs: update release guide
   chore: clean build script whitespace
   ```

5. 推送分支并开 PR：

   ```bash
   git push -u origin feat/short-description
   gh pr create --base main --fill
   ```

## 提交前检查

通用检查：

```bash
git status --short
git diff --check
bash -n build.sh && bash -n release.sh
```

Android 相关改动：

```bash
cd VoiceSyncAndroid
./gradlew test
./gradlew assembleDebug
```

发布前或改动签名、版本、构建配置时再跑：

```bash
cd VoiceSyncAndroid
./gradlew assembleRelease
```

macOS 相关改动：

```bash
cd VoiceSyncMac
xcodebuild test -project VoiceSyncMac.xcodeproj -scheme VoiceSyncMac -destination 'platform=macOS'
xcodebuild archive -project VoiceSyncMac.xcodeproj -scheme VoiceSyncMac -configuration Release -archivePath build/VoiceSyncMac.xcarchive CODE_SIGN_IDENTITY="" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO
```

Windows 相关改动：

```bash
python -m pip install -r VoiceSyncWindows/requirements.txt
python -m py_compile VoiceSyncWindows/*.py
cd VoiceSyncWindows
python main.py
```

在 Windows 上还应至少手动验证：应用能启动、显示本机地址、Android 能扫描到设备、文本和图片能写入剪贴板、自动粘贴和远程回车开关生效。

## 手动联调清单

跨端功能要重点测这些路径：

- 手机和电脑在同一个 Wi-Fi。
- 电脑端监听 `4500` 端口，开发模式可用 `4501`。
- Android 扫描 mDNS 设备并选择目标。
- 发送普通文本、多行文本、中文标点和较长文本。
- 发送单张图片和多张图片。
- 关闭自动粘贴时只写剪贴板；开启自动粘贴时内容进入当前光标位置。
- 开启和关闭“响应远程回车”分别验证行为。
- 端口被占用时，接收端不应继续广播不可用设备。

## Code Review 重点

Review 时优先看行为风险：

- Android 与电脑端 `/sync` JSON 字段是否兼容：`type`、`content`、`autoEnter`、`mimeType`。
- mDNS 服务类型是否保持 `_voicesync._tcp.` / `_voicesync._tcp.local.` 兼容。
- 剪贴板写入失败是否能正确返回失败，避免粘贴旧内容。
- 自动粘贴和自动回车是否尊重用户开关。
- 发布产物命名是否和 release workflow 里上传的文件名一致。
- README、更新机制、截图或 release 文案是否需要同步更新。

## 发布流程

正常发布使用 tag 触发 GitHub Actions。不要手动上传单个平台产物作为正式发布。

1. 确认 `main` 干净并已推到远端：

   ```bash
   git checkout main
   git pull --ff-only
   git status --short --branch
   ```

2. 选择版本号：

   - patch：`v0.3.1`，修 bug、小优化、文档修正
   - minor：`v0.4.0`，新增用户可见功能
   - major：`v1.0.0`，重大兼容性或产品定位变化

3. 创建并推送 tag：

   ```bash
   git tag -a v0.4.0 -m "VoiceSync v0.4.0"
   git push origin v0.4.0
   ```

4. 观察发布流水线：

   ```bash
   gh run list --workflow Release --limit 5
   gh run view <run-id> --json status,conclusion,jobs,url
   ```

5. 发布成功后确认三个资产都已上传：

   ```bash
   gh release view v0.4.0 --json tagName,name,url,publishedAt,assets --jq '{tagName,name,url,publishedAt,assets:[.assets[].name]}'
   ```

期望资产：

- `VoiceSync-Android-X.Y.Z.apk`
- `VoiceSyncMac-X.Y.Z.zip`
- `VoiceSync-Windows-X.Y.Z.zip`

## 发布失败处理

如果 tag 推错或 Actions 失败后需要重发同一个版本：

```bash
git tag -d v0.4.0
git push origin :refs/tags/v0.4.0
gh release delete v0.4.0 -y
git tag -a v0.4.0 -m "VoiceSync v0.4.0"
git push origin v0.4.0
```

如果只是某个平台构建失败，优先修代码或 workflow 后重新打 tag；不要把缺平台的 release 当正式版本发布。

## 本地手动发布备用流程

`release.sh` 会读取本地 `dist/` 目录并上传已有产物。它适合救急，不是默认路径。

```bash
./build.sh mac
./build.sh android
./build.sh windows
./release.sh
```

使用它之前先确认 `dist/` 至少包含：

- `VoiceSyncMac.app`
- `VoiceSync-Android.apk`
- `VoiceSync-Windows.zip`

## 维护原则

- 文档和脚本要跟真实命令同步，过期命令比没有文档更危险。
- 发布相关改动必须同时检查 `.github/workflows/release.yml`、`build.sh`、`release.sh` 和 `docs/UPDATE_MECHANISM.md`。
- 不要提交构建产物、密钥、证书、`.env` 或本地 IDE 状态文件。
- 新增跨端协议字段时，要同时更新 Android、Mac、Windows 和相关文档。
