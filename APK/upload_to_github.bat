@echo off
chcp 65001 >nul
title Upload to GitHub
setlocal
rem ===== 路径说明 =====
rem 本脚本放在 APK 文件夹里，双击即可上传代码到 GitHub。
rem 上级目录（..）就是 Android 工程 + git 仓库：
rem   ..\child\   = 孩子端模块
rem   ..\parent\  = 家长端模块
rem   ..\scripts\ = 两端共用脚本
rem 日志固定写在本文件夹：upload_log.txt
set "GIT=G:\HonorAppBlocker\Tools\PortableGit\bin\git.exe"
set "REPO=%~dp0.."
set "LOG=%~dp0upload_log.txt"

echo ===== %date% %time% start upload ===== > "%LOG%"

echo.
echo [1/2] Commit changes ...
echo [1/2] git add + commit >> "%LOG%"
"%GIT%" -C "%REPO%" add -A >> "%LOG%" 2>&1
"%GIT%" -C "%REPO%" commit -m "code update %date% %time%" >> "%LOG%" 2>&1
if %errorlevel% NEQ 0 (
    echo No new changes, continue to push ...
    echo [1/2] nothing to commit, continue >> "%LOG%"
)

echo [2/2] Push to GitHub (auto retry on network issues, please wait) ...
echo [2/2] git pull --rebase + push >> "%LOG%"
"%GIT%" -C "%REPO%" pull --rebase >> "%LOG%" 2>&1

set PUSH_OK=0
set /a TRY=1
:pushretry
"%GIT%" -C "%REPO%" push origin main >> "%LOG%" 2>&1
if %errorlevel% EQU 0 (
    set PUSH_OK=1
    goto :pushdone
)
echo Push failed, retry in 5 seconds (attempt %TRY%/3) ...
echo [2/2] push attempt %TRY% failed, retrying >> "%LOG%"
timeout /t 5 /nobreak >nul
set /a TRY+=1
if %TRY% LEQ 3 goto :pushretry

:pushdone
if %PUSH_OK% EQU 1 (
    echo.
    echo ========================================
    echo    OK! Code synced to GitHub
    echo ========================================
    echo ===== %date% %time% upload success ===== >> "%LOG%"
    pause
    exit /b 0
)

:fail
echo.
echo FAILED! Details written to upload_log.txt, opening now ...
echo ===== %date% %time% upload failed ===== >> "%LOG%"
start notepad "%LOG%"
pause
