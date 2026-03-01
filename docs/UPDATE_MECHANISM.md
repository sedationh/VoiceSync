# VoiceSync 自动更新机制

> 参考 EpubSpoon 项目实现，通过 GitHub Releases API 实现应用内更新检查。

---

## 工作原理

1. **版本号管理**：从 git tag 自动读取版本号（如 `v0.0.5`）
2. **CI 自动构建**：推送 tag 触发 GitHub Actions，自动构建 Android APK 和 Mac app
3. **发布到 Release**：构建产物自动发布到 GitHub Releases
4. **应用内检查**：用户点击"检查更新"，应用请求 GitHub API 获取最新版本并对比

---

## 发布新版本

```bash
# 1. 确保所有修改已提交
git add .
git commit -m "feat: 新功能描述"
git push

# 2. 打 tag（版本号遵循语义化版本）
git tag v0.0.6
git push origin v0.0.6

# 3. GitHub Actions 自动构建并发布，等待几分钟即可
```

### 版本号规则

- **patch**（`v0.0.x`）：bug 修复、小优化、UI 微调
- **minor**（`v0.x.0`）：新功能
- **major**（`vx.0.0`）：重大变更

---

## APK 签名说明

为确保用户可以覆盖安装（不用卸载旧版），CI 构建的 APK 与本地构建使用相同的 keystore：

- 使用：`~/.android/debug.keystore`（Android SDK 自带的调试签名）
- 已上传到：GitHub Secrets `DEBUG_KEYSTORE`
- 配置位置：`VoiceSyncAndroid/app/build.gradle.kts` 中的 `signingConfigs`

### 验证签名一致性

```bash
# 本地 keystore 指纹
keytool -list -keystore ~/.android/debug.keystore -storepass android

# CI 构建的 APK 指纹
apksigner verify --print-certs VoiceSync-Android-0.0.6.apk

# SHA-256 必须完全一致
```

---

## 更新检查流程

### Android 端

1. 用户点击"检查更新"按钮
2. `UpdateChecker.check()` 请求 GitHub API
3. 对比版本号（`x.y.z` 格式）
4. 如有新版本，弹出对话框显示更新说明
5. 用户点击"下载更新"，跳转浏览器下载 APK
6. 下载完成后点击安装即可覆盖旧版（签名一致）

### Mac 端

暂未实现自动更新检查，用户需手动前往 GitHub Releases 下载。

---

## 技术实现

### 1. 版本号管理 (`build.gradle.kts`)

```kotlin
fun gitVersionName(): String {
    val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
        .directory(rootDir).redirectErrorStream(true).start()
    val tag = process.inputStream.bufferedReader().readText().trim()
    return if (process.exitValue() == 0 && tag.isNotEmpty()) 
        tag.removePrefix("v") else "0.0.1"
}

fun gitVersionCode(): Int {
    val name = gitVersionName()
    val parts = name.split(".").map { it.toIntOrNull() ?: 0 }
    return parts[0] * 10000 + parts[1] * 100 + parts[2]
}
```

### 2. 更新检查 (`UpdateChecker.kt`)

```kotlin
suspend fun check(currentVersion: String): UpdateResult? {
    val url = "https://api.github.com/repos/sedationh/VoiceSync/releases/latest"
    // 请求 API，解析 tag_name 和 assets
    // 对比版本号
    // 返回结果
}
```

### 3. GitHub Actions (`.github/workflows/release.yml`)

```yaml
on:
  push:
    tags: ['v*']

jobs:
  build-and-release:
    - name: Build Android APK
    - name: Build Mac app
    - name: Create Release (上传 APK 和 ZIP)
```

---

## 常见问题

### Q: 如何删除错误的 tag？

```bash
# 删除本地和远程 tag
git tag -d v0.0.6
git push origin :refs/tags/v0.0.6

# 删除 GitHub Release
gh release delete v0.0.6 -y

# 重新打 tag
git tag v0.0.6
git push origin v0.0.6
```

### Q: 安装 APK 时提示"应用未安装"或"签名不一致"？

本地构建和 CI 构建的签名不一致，请检查：

1. `build.gradle.kts` 中是否显式指定了 keystore 路径
2. GitHub Secrets 中的 `DEBUG_KEYSTORE` 是否正确
3. 使用 `apksigner` 验证签名指纹

### Q: GitHub Actions 构建失败？

1. 检查 keystore secret 是否正确上传
2. 确保 tag 格式正确（`vX.Y.Z`）
3. 查看 Actions 日志定位具体错误

---

## 参考资源

- [EpubSpoon 项目](https://github.com/sedationh/EpubSpoon)
- [EpubSpoon UPDATE_MECHANISM.md](https://github.com/sedationh/EpubSpoon/blob/main/docs/UPDATE_MECHANISM.md)
- [GitHub Releases API 文档](https://docs.github.com/en/rest/releases/releases)
