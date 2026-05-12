@echo off
chcp 65001 >nul 2>&1
title VoiceSync Windows
cd /d "%~dp0"
py main.py
if errorlevel 1 python main.py
pause
