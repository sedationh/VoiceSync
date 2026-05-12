"""应用配置管理 - 使用 JSON 文件持久化到 %APPDATA%"""

import json
import os
from pathlib import Path

# 环境配置
DEBUG = os.environ.get("VOICESYNC_DEBUG", "").lower() in ("1", "true")
PORT = 4501 if DEBUG else 4500
APP_NAME = "VoiceSync (Dev)" if DEBUG else "VoiceSync"


class Config:
    """用户设置管理器，自动持久化到 %APPDATA%/VoiceSync/config.json"""

    _DEFAULTS = {
        "auto_paste": True,
        "auto_enter": True,
    }

    def __init__(self):
        appdata = os.environ.get("APPDATA", os.path.expanduser("~"))
        self._path = Path(appdata) / "VoiceSync" / "config.json"
        self._data = dict(self._DEFAULTS)
        self._load()

    def _load(self):
        try:
            if self._path.exists():
                with open(self._path, "r", encoding="utf-8") as f:
                    saved = json.load(f)
                # Only load known keys
                for key in self._DEFAULTS:
                    if key in saved:
                        self._data[key] = saved[key]
        except (json.JSONDecodeError, OSError):
            pass  # Use defaults on error

    def _save(self):
        try:
            self._path.parent.mkdir(parents=True, exist_ok=True)
            with open(self._path, "w", encoding="utf-8") as f:
                json.dump(self._data, f, indent=2, ensure_ascii=False)
        except OSError as e:
            print(f"⚠️ 保存配置失败: {e}")

    @property
    def auto_paste(self) -> bool:
        return self._data["auto_paste"]

    @auto_paste.setter
    def auto_paste(self, value: bool):
        self._data["auto_paste"] = value
        self._save()

    @property
    def auto_enter(self) -> bool:
        return self._data["auto_enter"]

    @auto_enter.setter
    def auto_enter(self, value: bool):
        self._data["auto_enter"] = value
        self._save()
