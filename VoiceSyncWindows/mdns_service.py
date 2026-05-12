"""mDNS 服务广播 - 让 Android 设备能自动发现 Windows 端

使用 zeroconf 库实现，与 Mac 端的 Bonjour/NetService 完全兼容。
服务类型: _voicesync._tcp.local.
"""

import socket
from typing import Optional

from zeroconf import Zeroconf, ServiceInfo


class MdnsService:
    """mDNS 服务广播管理器。"""

    def __init__(self):
        self._zeroconf: Optional[Zeroconf] = None
        self._info: Optional[ServiceInfo] = None
        self.is_publishing: bool = False
        self.service_name: Optional[str] = None

    def start(self, port: int, local_ip: str, device_name: Optional[str] = None):
        """开始向局域网广播 VoiceSync 服务。

        Args:
            port: HTTP 服务端口号
            local_ip: 本机局域网 IP 地址
            device_name: 自定义设备名称，默认使用 "VoiceSync-{主机名}"
        """
        self.stop()

        hostname = device_name or f"VoiceSync-{socket.gethostname()}"
        self.service_name = hostname

        self._info = ServiceInfo(
            type_="_voicesync._tcp.local.",
            name=f"{hostname}._voicesync._tcp.local.",
            addresses=[socket.inet_aton(local_ip)],
            port=port,
            properties={},
            server=f"{hostname}.local.",
        )

        self._zeroconf = Zeroconf()
        self._zeroconf.register_service(self._info)
        self.is_publishing = True
        print(f"📡 mDNS 广播已启动: {hostname} ({local_ip}:{port})")

    def stop(self):
        """停止广播服务。"""
        if self._zeroconf and self._info:
            try:
                self._zeroconf.unregister_service(self._info)
            except Exception:
                pass
            try:
                self._zeroconf.close()
            except Exception:
                pass

        self._zeroconf = None
        self._info = None
        self.is_publishing = False
        self.service_name = None

    def __del__(self):
        self.stop()
