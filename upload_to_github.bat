@echo off
chcp 65001 >nul
title Upload to GitHub
setlocal
set "GIT=G:\HonorAppBlocker\Tools\PortableGit\bin\git.exe"
set "SRC=G:\HonorAppBlocker\v3_mqtt"
set "REPO=G:\HonorAppBlocker\Panyutian_app"
rem Log file: English name. Default next to this .bat; wrappers may override via UPLOAD_LOG
if defined UPLOAD_LOG (
    set "LOG=%UPLOAD_LOG%"
) else (
    set "LOG=%~dp0upload.txt"
)

echo ===== %date% %time% start upload ===== > "%LOG%"

echo.
echo [1/3] Sync code to repo ...
echo [1/3] robocopy start >> "%LOG%"
robocopy "%SRC%" "%REPO%" /E /XD build .gradle .kotlin .idea APK .git /XF *.apk *.jks *.keystore local.properties /NFL /NDL /NJH /NJS /NP >> "%LOG%" 2>&1
if %errorlevel% GEQ 8 (
    echo [1/3] sync failed, exit code %errorlevel% >> "%LOG%"
    goto :fail
)

echo [2/3] Commit changes ...
echo [2/3] git add + commit >> "%LOG%"
"%GIT%" -C "%REPO%" add -A >> "%LOG%" 2>&1
"%GIT%" -C "%REPO%" commit -m "code update %date% %time%" >> "%LOG%" 2>&1
if %errorlevel% NEQ 0 (
    echo No new changes, continue to push ...
    echo [2/3] nothing to commit, continue >> "%LOG%"
)

echo [3/3] Push to GitHub (auto retry on network issues, please wait) ...
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
echo Push failed, retry in 5 seconds (attempt %TRY%/3) ...
echo [3/3] push attempt %TRY% failed, retrying >> "%LOG%"
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
echo FAILED! Details written to log, opening now ...
echo ===== %date% %time% upload failed ===== >> "%LOG%"
start notepad "%LOG%"
pause
