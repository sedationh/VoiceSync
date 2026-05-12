"""VoiceSync Windows - 让手机的语音识别，为电脑打字。

在手机上用语音输入法说话，文字自动同步到 Windows 剪贴板。
"""

import sys
import os


def main():
    # 检查 Python 版本
    if sys.version_info < (3, 8):
        print("❌ 需要 Python 3.8 或更高版本")
        sys.exit(1)

    # 检查必要依赖
    missing = []
    try:
        import zeroconf  # noqa: F401
    except ImportError:
        missing.append("zeroconf")
    try:
        import pystray  # noqa: F401
    except ImportError:
        missing.append("pystray")
    try:
        import PIL  # noqa: F401
    except ImportError:
        missing.append("Pillow")

    if missing:
        print(f"⚠️ 缺少依赖: {', '.join(missing)}")
        print(f"请运行: pip install {' '.join(missing)}")
        print("或者: pip install -r requirements.txt")

        # 如果只是缺少托盘相关依赖，仍然可以运行（无托盘模式）
        if missing == ["pystray"] or missing == ["Pillow"] or set(missing) == {"pystray", "Pillow"}:
            print("将以无系统托盘模式运行...")
        elif "zeroconf" in missing:
            print("❌ zeroconf 是必需依赖（mDNS 设备发现），请先安装")
            sys.exit(1)

    from gui import VoiceSyncApp

    app = VoiceSyncApp()
    app.run()


if __name__ == "__main__":
    main()
