@echo off
chcp 65001 >nul
title 一键上传到 GitHub
setlocal
set "GIT=G:\HonorAppBlocker\Tools\PortableGit\bin\git.exe"
set "SRC=G:\HonorAppBlocker\v3_mqtt"
set "REPO=G:\HonorAppBlocker\Panyutian_app"
set "LOG=G:\HonorAppBlocker\上传日志.txt"

echo ===== %date% %time% 开始上传 ===== > "%LOG%"

echo.
echo [1/3] 同步最新代码到仓库...
echo [1/3] robocopy 同步开始 >> "%LOG%"
robocopy "%SRC%" "%REPO%" /E /XD build .gradle .kotlin .idea APK .git /XF *.apk *.jks *.keystore local.properties /NFL /NDL /NJH /NJS /NP >> "%LOG%" 2>&1
if %errorlevel% GEQ 8 (
    echo [1/3] ❌ 同步失败，退出码 %errorlevel% >> "%LOG%"
    goto :fail
)

echo [2/3] 提交改动...
echo [2/3] git add + commit >> "%LOG%"
"%GIT%" -C "%REPO%" add -A >> "%LOG%" 2>&1
"%GIT%" -C "%REPO%" commit -m "代码更新 %date% %time%" >> "%LOG%" 2>&1
if %errorlevel% NEQ 0 (
    echo 没有新的改动，继续检查推送状态...
    echo [2/3] 没有改动，继续推送流程 >> "%LOG%"
)

echo [3/3] 推送到 GitHub（网络波动会自动重试，请耐心等待）...
echo [3/3] git pull --rebase + push >> "%LOG%"
"%GIT%" -C "%REPO%" pull --rebase >> "%LOG%" 2>&1

set PUSH_OK=0
set /a TRY=1
:pushretry
"%GIT%" -C "%REPO%" push origin main >> "%LOG%" 2>&1
if %errorlevel% EQU 0 (
    set PUSH_OK=1
    goto :pushdone
)
echo 推送失败，5 秒后自动重试（第 %TRY% 次/最多 3 次）...
echo [3/3] push 第 %TRY% 次失败（网络波动），自动重试 >> "%LOG%"
timeout /t 5 /nobreak >nul
set /a TRY+=1
if %TRY% LEQ 3 goto :pushretry

:pushdone
if %PUSH_OK% EQU 1 (
    echo.
    echo ========================================
    echo    ✅ 上传成功！代码已同步到 GitHub
    echo ========================================
    echo ===== %date% %time% 上传成功 ===== >> "%LOG%"
    pause
    exit /b 0
)

:fail
echo.
echo ❌ 上传失败！详细原因已写入日志，即将打开...
echo ===== %date% %time% 上传失败 ===== >> "%LOG%"
start notepad "%LOG%"
pause
