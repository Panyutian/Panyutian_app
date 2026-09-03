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
rem 详细日志写在本文件夹：upload_log.txt
set "GIT=G:\HonorAppBlocker\Tools\PortableGit\bin\git.exe"
set "REPO=%~dp0.."
set "LOG=%~dp0upload_log.txt"

echo ===== %date% %time% start upload ===== > "%LOG%"
echo [INFO] REPO = %REPO% >> "%LOG%"
echo [INFO] GIT  = %GIT% >> "%LOG%"
echo [%time%] branch: >> "%LOG%"
"%GIT%" -C "%REPO%" rev-parse --abbrev-ref HEAD >> "%LOG%" 2>&1
echo [%time%] remote: >> "%LOG%"
"%GIT%" -C "%REPO%" remote -v >> "%LOG%" 2>&1
echo. >> "%LOG%"

echo.
echo [1/2] Commit changes ...
echo [1/2] ===== git add + commit ===== >> "%LOG%"
echo [%time%] ^> git add -A >> "%LOG%"
"%GIT%" -C "%REPO%" add -A >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [%time%] changed files (git status --porcelain): >> "%LOG%"
"%GIT%" -C "%REPO%" status --porcelain >> "%LOG%" 2>&1

echo. >> "%LOG%"
echo [%time%] ^> git commit >> "%LOG%"
"%GIT%" -C "%REPO%" commit -m "code update %date% %time%" >> "%LOG%" 2>&1
if %errorlevel% NEQ 0 (
    echo [%time%] result: nothing to commit, working tree clean >> "%LOG%"
    echo No new changes, continue to push ...
) else (
    echo [%time%] result: commit OK, latest commit: >> "%LOG%"
    "%GIT%" -C "%REPO%" log -1 --oneline >> "%LOG%" 2>&1
)

echo.
echo [2/2] Push to GitHub (auto retry on network issues, please wait) ...
echo. >> "%LOG%"
echo [2/2] ===== git pull --rebase + push ===== >> "%LOG%"
echo [%time%] ^> git pull --rebase >> "%LOG%"
"%GIT%" -C "%REPO%" pull --rebase >> "%LOG%" 2>&1
echo. >> "%LOG%"

set PUSH_OK=0
set /a TRY=1
:pushretry
echo [%time%] ^> git push origin main (attempt %TRY%/3) >> "%LOG%"
"%GIT%" -C "%REPO%" push origin main >> "%LOG%" 2>&1
if %errorlevel% EQU 0 (
    set PUSH_OK=1
    goto :pushdone
)
echo Push failed, retry in 5 seconds (attempt %TRY%/3) ...
echo [%time%] result: push attempt %TRY% FAILED, retry in 5 seconds >> "%LOG%"
echo. >> "%LOG%"
timeout /t 5 /nobreak >nul
set /a TRY+=1
if %TRY% LEQ 3 goto :pushretry

:pushdone
echo. >> "%LOG%"
if %PUSH_OK% EQU 1 (
    echo [%time%] ===== push OK, final state ===== >> "%LOG%"
    "%GIT%" -C "%REPO%" log -1 --oneline >> "%LOG%" 2>&1
    "%GIT%" -C "%REPO%" status -sb >> "%LOG%" 2>&1
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
