"""键盘输入模拟 - 通过 Win32 SendInput API 模拟按键"""

import ctypes
import ctypes.wintypes as wintypes

# 常量
INPUT_KEYBOARD = 1
KEYEVENTF_KEYUP = 0x0002

VK_CONTROL = 0x11
VK_RETURN = 0x0D


# Win32 INPUT 结构体（64 位兼容）
class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ctypes.c_void_p),  # ULONG_PTR
    ]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", ctypes.c_long),
        ("dy", ctypes.c_long),
        ("mouseData", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ctypes.c_void_p),
    ]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [
        ("uMsg", wintypes.DWORD),
        ("wParamL", wintypes.WORD),
        ("wParamH", wintypes.WORD),
    ]


class _INPUT_UNION(ctypes.Union):
    _fields_ = [
        ("mi", MOUSEINPUT),  # 最大的成员放在前面确保 union 大小正确
        ("ki", KEYBDINPUT),
        ("hi", HARDWAREINPUT),
    ]


class INPUT(ctypes.Structure):
    _fields_ = [
        ("type", wintypes.DWORD),
        ("union", _INPUT_UNION),
    ]


# 设置 SendInput 函数签名
_SendInput = ctypes.windll.user32.SendInput
_SendInput.argtypes = [wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int]
_SendInput.restype = wintypes.UINT


def _make_key_input(vk: int, flags: int = 0) -> INPUT:
    """创建一个键盘 INPUT 结构体。"""
    inp = INPUT()
    inp.type = INPUT_KEYBOARD
    inp.union.ki.wVk = vk
    inp.union.ki.dwFlags = flags
    return inp


def _send_keys(*inputs: INPUT):
    """批量发送按键事件。"""
    arr = (INPUT * len(inputs))(*inputs)
    _SendInput(len(inputs), arr, ctypes.sizeof(INPUT))


def simulate_paste():
    """模拟 Ctrl+V 粘贴操作。"""
    _send_keys(
        _make_key_input(VK_CONTROL),          # Ctrl 按下
        _make_key_input(ord("V")),             # V 按下
        _make_key_input(ord("V"), KEYEVENTF_KEYUP),  # V 释放
        _make_key_input(VK_CONTROL, KEYEVENTF_KEYUP),  # Ctrl 释放
    )


def simulate_enter():
    """模拟 Enter 回车键。"""
    _send_keys(
        _make_key_input(VK_RETURN),
        _make_key_input(VK_RETURN, KEYEVENTF_KEYUP),
    )
