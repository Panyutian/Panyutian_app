@echo off
chcp 65001 >nul
title 一键上传到 GitHub
setlocal
set "GIT=G:\HonorAppBlocker\Tools\PortableGit\bin\git.exe"
set "SRC=G:\HonorAppBlocker\v3_mqtt"
set "REPO=G:\HonorAppBlocker\Panyutian_app"

echo.
echo ========================================
echo        一键上传代码到 GitHub
echo ========================================
echo.

echo [1/3] 同步最新代码到仓库...
robocopy "%SRC%" "%REPO%" /E /XD build .gradle .kotlin .idea APK .git /XF *.apk *.jks *.keystore local.properties /NFL /NDL /NJH /NJS /NP
if %errorlevel% GEQ 8 (
    echo ❌ 代码同步失败，请关闭可能占用文件的程序后重试
    pause
    exit /b 1
)

echo [2/3] 提交改动...
cd /d "%REPO%"
"%GIT%" add -A
"%GIT%" commit -m "代码更新 %date% %time%" -q
if %errorlevel% NEQ 0 (
    echo 没有检测到代码改动，无需上传。
    echo.
    pause
    exit /b 0
)

echo [3/3] 推送到 GitHub...
"%GIT%" pull --rebase -q
"%GIT%" push origin main
if %errorlevel% NEQ 0 (
    echo.
    echo ❌ 上传失败！常见原因：
    echo    1. 网络不稳定 —— 点一下脚本重试即可
    echo    2. 首次上传 —— 请在弹出的浏览器窗口里登录 GitHub 并点授权
) else (
    echo.
    echo ========================================
    echo    ✅ 上传成功！代码已同步到 GitHub
    echo ========================================
)
echo.
pause
