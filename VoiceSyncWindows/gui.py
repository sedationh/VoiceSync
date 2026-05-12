"""GUI 界面 - tkinter 主窗口 + pystray 系统托盘"""

import base64
import threading
import tkinter as tk
from tkinter import ttk, messagebox
from datetime import datetime
from typing import List, Tuple

from config import Config, PORT, APP_NAME
from server import SyncServer
from clipboard import set_text, set_image
from input_simulator import simulate_paste, simulate_enter
from network import get_local_ip
from mdns_service import MdnsService

# 可选依赖：系统托盘
try:
    import pystray
    from PIL import Image, ImageDraw

    HAS_TRAY = True
except ImportError:
    HAS_TRAY = False
    print("⚠️ pystray/Pillow 未安装，系统托盘功能不可用")


class VoiceSyncApp:
    """VoiceSync Windows 主应用。"""

    def __init__(self):
        self.config = Config()
        self.history: List[Tuple[str, str]] = []  # [(time_str, content), ...]
        self.local_ip = get_local_ip()
        self.server: SyncServer = None
        self.mdns = MdnsService()
        self.tray_icon = None
        self.is_running = False

        self._build_ui()
        self._start_services()
        if HAS_TRAY:
            self._setup_tray()

    # ─── UI 构建 ────────────────────────────────────────────

    def _build_ui(self):
        self.root = tk.Tk()
        self.root.title(APP_NAME)
        self.root.geometry("400x540")
        self.root.minsize(360, 420)
        self.root.configure(bg="#f0f0f0")
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

        # 尝试设置 DPI 感知
        try:
            self.root.tk.call("tk", "scaling", self.root.winfo_fpixels("1i") / 72)
        except Exception:
            pass

        style = ttk.Style()
        style.theme_use("vista" if "vista" in style.theme_names() else "default")

        main = ttk.Frame(self.root, padding=12)
        main.pack(fill=tk.BOTH, expand=True)

        # 状态栏
        self._build_status_bar(main)
        # IP 地址栏
        self._build_ip_bar(main)
        # mDNS 状态
        self._build_mdns_bar(main)

        ttk.Separator(main).pack(fill=tk.X, pady=6)

        # 设置区
        self._build_settings(main)

        ttk.Separator(main).pack(fill=tk.X, pady=6)

        # 历史记录
        self._build_history(main)

    def _build_status_bar(self, parent):
        frame = ttk.Frame(parent)
        frame.pack(fill=tk.X, pady=(0, 6))

        self.status_canvas = tk.Canvas(
            frame, width=10, height=10, highlightthickness=0
        )
        self.status_canvas.pack(side=tk.LEFT, padx=(0, 6))

        self.status_label = ttk.Label(frame, text="启动中...", font=("Segoe UI", 10))
        self.status_label.pack(side=tk.LEFT)

        self.count_label = ttk.Label(frame, text="", font=("Segoe UI", 9))
        self.count_label.pack(side=tk.RIGHT)

    def _build_ip_bar(self, parent):
        frame = ttk.Frame(parent)
        frame.pack(fill=tk.X, pady=(0, 4))

        address = f"{self.local_ip}:{PORT}"
        self.ip_var = tk.StringVar(value=address)

        ip_label = ttk.Label(
            frame, textvariable=self.ip_var, font=("Consolas", 11), cursor="hand2"
        )
        ip_label.pack(side=tk.LEFT)
        ip_label.bind("<Button-1>", self._copy_address)

        self.copy_tip = ttk.Label(frame, text="", foreground="green", font=("Segoe UI", 9))
        self.copy_tip.pack(side=tk.RIGHT)

    def _build_mdns_bar(self, parent):
        self.mdns_label = ttk.Label(
            parent, text="📡 广播启动中...", font=("Segoe UI", 9), foreground="gray"
        )
        self.mdns_label.pack(fill=tk.X, pady=(0, 2))

    def _build_settings(self, parent):
        frame = ttk.Frame(parent)
        frame.pack(fill=tk.X, pady=2)

        self.auto_paste_var = tk.BooleanVar(value=self.config.auto_paste)
        self.auto_enter_var = tk.BooleanVar(value=self.config.auto_enter)

        ttk.Checkbutton(
            frame, text="自动粘贴 (Ctrl+V)",
            variable=self.auto_paste_var,
            command=self._on_toggle_auto_paste,
        ).pack(side=tk.LEFT, padx=(0, 20))

        ttk.Checkbutton(
            frame, text="响应远程回车",
            variable=self.auto_enter_var,
            command=self._on_toggle_auto_enter,
        ).pack(side=tk.LEFT)

    def _build_history(self, parent):
        header = ttk.Frame(parent)
        header.pack(fill=tk.X, pady=(2, 4))

        ttk.Label(header, text="同步历史", font=("Segoe UI", 10, "bold")).pack(side=tk.LEFT)
        ttk.Button(header, text="清空", command=self._clear_history).pack(side=tk.RIGHT)

        # Treeview 显示历史记录
        list_frame = ttk.Frame(parent)
        list_frame.pack(fill=tk.BOTH, expand=True)

        columns = ("time", "content")
        self.tree = ttk.Treeview(
            list_frame, columns=columns, show="headings", selectmode="browse"
        )
        self.tree.heading("time", text="时间")
        self.tree.heading("content", text="内容")
        self.tree.column("time", width=65, minwidth=60, stretch=False)
        self.tree.column("content", width=280, minwidth=100)

        scrollbar = ttk.Scrollbar(list_frame, orient=tk.VERTICAL, command=self.tree.yview)
        self.tree.configure(yscrollcommand=scrollbar.set)
        self.tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)

        # 双击复制
        self.tree.bind("<Double-1>", self._on_history_double_click)

        # 底部提示
        ttk.Label(
            parent, text="双击条目可复制到剪贴板",
            font=("Segoe UI", 8), foreground="gray"
        ).pack(pady=(4, 0))

    # ─── 服务启动 ────────────────────────────────────────────

    def _start_services(self):
        # HTTP 服务器
        try:
            self.server = SyncServer(PORT, self._on_sync_received)
            self.server.start()
            self.is_running = True
            self._update_status(True)
            print(f"[{APP_NAME}] HTTP 服务启动在端口 {PORT}")
        except Exception as e:
            self.is_running = False
            self._update_status(False, str(e))
            self._update_mdns_status(False)
            print(f"[{APP_NAME}] HTTP 服务启动失败: {e}")
            return  # HTTP 失败时不广播 mDNS，避免 Android 发现不可用的设备

        # mDNS 广播（仅在 HTTP 启动成功后）
        try:
            self.mdns.start(PORT, self.local_ip)
            self._update_mdns_status(True)
        except Exception as e:
            self._update_mdns_status(False)
            print(f"[{APP_NAME}] mDNS 广播启动失败: {e}")

    # ─── 同步处理 ────────────────────────────────────────────

    def _on_sync_received(self, sync_type, content, auto_enter, mime_type):
        """HTTP 服务器回调（在服务器线程中），调度到主线程处理。"""
        self.root.after(0, lambda: self._handle_sync(sync_type, content, auto_enter, mime_type))

    def _handle_sync(self, sync_type, content, auto_enter, mime_type):
        """在主线程中处理同步数据。"""
        if sync_type == "image":
            try:
                image_data = base64.b64decode(content)
                success = set_image(image_data)
                size_kb = len(image_data) // 1024
                display = f"[图片 {size_kb}KB]"
            except Exception as e:
                print(f"❌ 图片处理失败: {e}")
                return
        else:
            success = set_text(content)
            display = content

        if success:
            self._add_history(display)
            print(f"✅ {'图片' if sync_type == 'image' else '文本'}已写入剪贴板")
        else:
            print(f"❌ 写入剪贴板失败")
            return

        # 自动粘贴
        if self.config.auto_paste:
            self.root.after(150, lambda: self._do_auto_paste(auto_enter))

    def _do_auto_paste(self, auto_enter):
        simulate_paste()
        if auto_enter and self.config.auto_enter:
            self.root.after(200, simulate_enter)

    # ─── 历史记录 ────────────────────────────────────────────

    def _add_history(self, content: str):
        now = datetime.now().strftime("%H:%M:%S")
        self.history.insert(0, (now, content))
        if len(self.history) > 50:
            self.history.pop()
        self._refresh_history_ui()

    def _refresh_history_ui(self):
        self.tree.delete(*self.tree.get_children())
        for time_str, text in self.history:
            display = (text[:80] + "...") if len(text) > 80 else text
            # 去掉换行符避免 Treeview 显示问题
            display = display.replace("\n", " ").replace("\r", "")
            self.tree.insert("", tk.END, values=(time_str, display))
        self.count_label.configure(text=str(len(self.history)) if self.history else "")

    def _on_history_double_click(self, event):
        selected = self.tree.selection()
        if not selected:
            return
        idx = self.tree.index(selected[0])
        if 0 <= idx < len(self.history):
            full_content = self.history[idx][1]
            set_text(full_content)
            self.copy_tip.configure(text="已复制!")
            self.root.after(1500, lambda: self.copy_tip.configure(text=""))

    def _clear_history(self):
        if not self.history:
            return
        if messagebox.askyesno("确认清空", "确定要清空所有历史记录吗？"):
            self.history.clear()
            self._refresh_history_ui()

    # ─── UI 更新 ────────────────────────────────────────────

    def _update_status(self, running: bool, error: str = None):
        self.status_canvas.delete("all")
        if running:
            self.status_canvas.create_oval(1, 1, 9, 9, fill="#22c55e", outline="")
            self.status_label.configure(text="运行中", foreground="#16a34a")
        else:
            self.status_canvas.create_oval(1, 1, 9, 9, fill="#ef4444", outline="")
            text = f"启动失败: {error}" if error else "已停止"
            self.status_label.configure(text=text, foreground="#dc2626")

    def _update_mdns_status(self, publishing: bool):
        if publishing:
            name = self.mdns.service_name or ""
            self.mdns_label.configure(
                text=f"📡 正在广播到局域网 ({name})", foreground="#16a34a"
            )
        else:
            self.mdns_label.configure(text="📡 广播未启动", foreground="#ea580c")

    def _copy_address(self, event=None):
        self.root.clipboard_clear()
        self.root.clipboard_append(self.ip_var.get())
        self.copy_tip.configure(text="已复制!")
        self.root.after(1500, lambda: self.copy_tip.configure(text=""))

    def _on_toggle_auto_paste(self):
        self.config.auto_paste = self.auto_paste_var.get()

    def _on_toggle_auto_enter(self):
        self.config.auto_enter = self.auto_enter_var.get()

    # ─── 系统托盘 ────────────────────────────────────────────

    def _setup_tray(self):
        icon_image = self._create_tray_icon()
        menu = pystray.Menu(
            pystray.MenuItem("显示主窗口", self._tray_show, default=True),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem(
                f"{self.local_ip}:{PORT}",
                None,
                enabled=False,
            ),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("退出", self._tray_quit),
        )
        self.tray_icon = pystray.Icon(APP_NAME, icon_image, APP_NAME, menu)
        threading.Thread(target=self.tray_icon.run, daemon=True, name="TrayIcon").start()

    def _create_tray_icon(self) -> "Image":
        """生成一个简单的绿色圆形 V 字图标。"""
        img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        d.ellipse([2, 2, 62, 62], fill="#22c55e")
        # 绘制 V 字
        d.line([(18, 18), (32, 46)], fill="white", width=4)
        d.line([(32, 46), (46, 18)], fill="white", width=4)
        return img

    def _tray_show(self, icon=None, item=None):
        self.root.after(0, self._do_show_window)

    def _do_show_window(self):
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()

    def _tray_quit(self, icon=None, item=None):
        self.root.after(0, self._do_quit)

    # ─── 窗口与生命周期 ────────────────────────────────────────

    def _on_close(self):
        """关闭窗口时：有托盘则最小化到托盘，否则退出。"""
        if HAS_TRAY and self.tray_icon:
            self.root.withdraw()
        else:
            self._do_quit()

    def _do_quit(self):
        print(f"[{APP_NAME}] 正在退出...")
        if self.tray_icon:
            try:
                self.tray_icon.stop()
            except Exception:
                pass
        self.mdns.stop()
        if self.server:
            self.server.stop()
        self.root.destroy()

    def run(self):
        """启动主事件循环。"""
        self.root.mainloop()
