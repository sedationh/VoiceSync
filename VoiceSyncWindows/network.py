"""网络工具 - 获取本机局域网 IP 地址"""

import socket


def get_local_ip() -> str:
    """通过 UDP 连接技巧获取本机在局域网中的 IP 地址。

    不会真正发送数据，只是让系统选择正确的网络接口。
    """
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(1)
        # 连接一个外部地址（不会真正发包），让系统选择正确的接口
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        # Fallback: 通过 hostname 解析
        try:
            return socket.gethostbyname(socket.gethostname())
        except Exception:
            return "127.0.0.1"
