@echo off
chcp 65001 >nul
REM 以管理员权限运行 PowerShell 激活脚本（解决设备驱动、权限问题）

:: 先检查管理员身份
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo 正在请求管理员权限...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

cd /d "%~dp0"
title [管理员模式] 荣耀应用管控 - DeviceOwner 激活
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp002_激活DeviceOwner.ps1"
pause
