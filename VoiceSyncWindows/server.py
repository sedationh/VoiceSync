"""HTTP 同步服务器 - 接收 Android 端发来的文本和图片数据"""

import json
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler
from typing import Callable, Optional


def _create_handler(callback: Callable):
    """创建带回调的请求处理器（闭包模式，避免类变量共享）。"""

    class SyncHandler(BaseHTTPRequestHandler):

        def do_POST(self):
            if self.path != "/sync":
                self.send_response(404)
                self.end_headers()
                return

            content_length = int(self.headers.get("Content-Length", 0))
            if content_length == 0:
                self._respond(400, "Empty body")
                return

            body = self.rfile.read(content_length).decode("utf-8")

            try:
                data = json.loads(body)
            except json.JSONDecodeError:
                # 兼容纯文本格式（向下兼容）
                callback("text", body, False, "")
                self._respond(200, "Success")
                return

            sync_type = data.get("type", "text")
            content = data.get("content", "")
            auto_enter = data.get("autoEnter", False)
            mime_type = data.get("mimeType", "image/png")

            if not content:
                self._respond(400, "Empty content")
                return

            callback(sync_type, content, auto_enter, mime_type)
            self._respond(200, "Success")

        def do_GET(self):
            """健康检查端点"""
            if self.path == "/health":
                self._respond(200, "OK")
            else:
                self.send_response(404)
                self.end_headers()

        def _respond(self, code: int, message: str):
            self.send_response(code)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(message.encode("utf-8"))

        def log_message(self, format, *args):
            # 静默 HTTP 请求日志，避免刷屏
            pass

    return SyncHandler


class SyncServer:
    """线程安全的 HTTP 同步服务器。"""

    def __init__(self, port: int, callback: Callable):
        handler = _create_handler(callback)
        self._server = HTTPServer(("0.0.0.0", port), handler)
        self._thread: Optional[threading.Thread] = None

    def start(self):
        """在后台线程中启动服务器。"""
        self._thread = threading.Thread(
            target=self._server.serve_forever,
            daemon=True,
            name="SyncServer",
        )
        self._thread.start()

    def stop(self):
        """停止服务器。"""
        self._server.shutdown()
