"""Windows 剪贴板操作 - 通过 Win32 API (ctypes) 直接操作系统剪贴板"""

import ctypes
import ctypes.wintypes as wintypes
import io
import time

# Win32 剪贴板格式常量
CF_UNICODETEXT = 13
CF_DIB = 8
GHND = 0x0042  # GlobalAlloc flags: GMEM_MOVEABLE | GMEM_ZEROINIT

# Win32 API
user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

# 设置函数签名（确保 64 位兼容）
user32.OpenClipboard.argtypes = [wintypes.HWND]
user32.OpenClipboard.restype = wintypes.BOOL
user32.CloseClipboard.argtypes = []
user32.CloseClipboard.restype = wintypes.BOOL
user32.EmptyClipboard.argtypes = []
user32.EmptyClipboard.restype = wintypes.BOOL
user32.SetClipboardData.argtypes = [wintypes.UINT, wintypes.HANDLE]
user32.SetClipboardData.restype = wintypes.HANDLE
user32.RegisterClipboardFormatW.argtypes = [wintypes.LPCWSTR]
user32.RegisterClipboardFormatW.restype = wintypes.UINT

kernel32.GlobalAlloc.argtypes = [wintypes.UINT, ctypes.c_size_t]
kernel32.GlobalAlloc.restype = ctypes.c_void_p
kernel32.GlobalLock.argtypes = [ctypes.c_void_p]
kernel32.GlobalLock.restype = ctypes.c_void_p
kernel32.GlobalUnlock.argtypes = [ctypes.c_void_p]
kernel32.GlobalUnlock.restype = wintypes.BOOL


def _open_clipboard_retry(max_retries: int = 5, delay: float = 0.05) -> bool:
    """尝试打开剪贴板（带重试），其他程序可能正在占用。"""
    for _ in range(max_retries):
        if user32.OpenClipboard(0):
            return True
        time.sleep(delay)
    return False


def _alloc_and_set(data: bytes, cf_format: int) -> bool:
    """分配全局内存并设置到剪贴板。"""
    h_mem = kernel32.GlobalAlloc(GHND, len(data))
    if not h_mem:
        return False
    p_mem = kernel32.GlobalLock(h_mem)
    if not p_mem:
        return False
    ctypes.memmove(p_mem, data, len(data))
    kernel32.GlobalUnlock(h_mem)
    # SetClipboardData 成功后，内存归系统所有，不要 GlobalFree
    result = user32.SetClipboardData(cf_format, h_mem)
    return bool(result)


def set_text(text: str) -> bool:
    """将文本写入 Windows 剪贴板。"""
    if not _open_clipboard_retry():
        print("❌ 无法打开剪贴板")
        return False
    try:
        user32.EmptyClipboard()
        # UTF-16 LE 编码 + null terminator
        data = text.encode("utf-16-le") + b"\x00\x00"
        return _alloc_and_set(data, CF_UNICODETEXT)
    finally:
        user32.CloseClipboard()


def set_image(image_data: bytes) -> bool:
    """将图片数据写入 Windows 剪贴板。

    同时设置 CF_DIB（通用兼容）和 PNG 格式（现代应用支持）。
    """
    try:
        from PIL import Image

        img = Image.open(io.BytesIO(image_data))

        # 转换为 BMP 格式，提取 DIB 数据（去掉 14 字节的 BMP 文件头）
        bmp_buf = io.BytesIO()
        img.convert("RGB").save(bmp_buf, "BMP")
        bmp_bytes = bmp_buf.getvalue()
        dib_data = bmp_bytes[14:]  # 跳过 BITMAPFILEHEADER

        # 确保图片数据是 PNG 格式（用于 PNG 剪贴板格式）
        png_buf = io.BytesIO()
        img.save(png_buf, "PNG")
        png_data = png_buf.getvalue()

    except Exception as e:
        print(f"❌ 图片解码失败: {e}")
        return False

    if not _open_clipboard_retry():
        print("❌ 无法打开剪贴板")
        return False

    try:
        user32.EmptyClipboard()

        # 设置 CF_DIB 格式（广泛兼容）
        dib_ok = _alloc_and_set(dib_data, CF_DIB)
        if not dib_ok:
            print("⚠️ CF_DIB 格式写入失败")

        # 设置 PNG 格式（现代应用如 Chrome, Office 支持）
        png_ok = False
        png_format = user32.RegisterClipboardFormatW("PNG")
        if png_format:
            png_ok = _alloc_and_set(png_data, png_format)
            if not png_ok:
                print("⚠️ PNG 格式写入失败")

        if not dib_ok and not png_ok:
            print("❌ 所有图片格式写入剪贴板均失败")
            return False
        return True
    finally:
        user32.CloseClipboard()
