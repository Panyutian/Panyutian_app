@echo off
chcp 65001 > nul
title ============================================
title  荣耀应用管控 - 一键安装APK+启动+激活DeviceOwner
title ============================================
setlocal enabledelayedexpansion

:: ===== 脚本位置：HonorAppBlocker/scripts/一键安装APK并激活DeviceOwner.bat =====
:: 功能：APK 已事先编好的情况下，3步装机：
::   1. 查手机连接（ADB）
::   2. 安装 app-debug.apk 到手机（先找 app/build/outputs/apk/debug/app-debug.apk；
::      没找到就用本脚本同目录的 app-debug.apk；还没找到提示用户拖进来）
::   3. 启动 App
::   4. 执行 DeviceOwner 激活
:: ===== 前置准备 =====
::   - 电脑已配好 ADB，或已装 Android Studio
::   - 手机已开 USB 调试，插线并允许调试
::   - 手机已移除 Google 账号、已关闭多用户/隐私空间

echo.
echo ===========================================================
echo   📦 荣耀应用管控 - 一键安装APK + 激活 DeviceOwner
echo ===========================================================
echo.

:: 切到脚本所在目录，找 APK
cd /d "%~dp0"
set "SCRIPT_DIR=%cd%"
cd ..
set "PROJECT_DIR=%cd%"

:: -------------------- 1. 找 APK --------------------
echo [1/5] 寻找 APK 文件...
set "APK_PATH="
set "CANDIDATE1=%PROJECT_DIR%\app\build\outputs\apk\debug\app-debug.apk"
set "CANDIDATE2=%SCRIPT_DIR%\app-debug.apk"

if exist "%CANDIDATE1%" (
    set "APK_PATH=%CANDIDATE1%"
    echo [OK] 找到编译产物: %APK_PATH%
) else if exist "%CANDIDATE2%" (
    set "APK_PATH=%CANDIDATE2%"
    echo [OK] 找到脚本同目录 APK: %APK_PATH%
) else (
    echo.
    echo ⚠️  没找到现成的 app-debug.apk
    echo   方式1: 先在 Android Studio 里 Build → Build Bundle(s)/APK → Build APK(s)
    echo          编译好后会放在 app\build\outputs\apk\debug\app-debug.apk
    echo   方式2: 也可以把 app-debug.apk 文件拖到这个窗口里，然后按回车
    echo.
    set /p "PASTE=请输入 APK 的完整路径（或拖文件进此窗口）: "
    if not exist "!PASTE!" (
        echo ❌ 文件不存在，退出
        pause
        exit /b 1
    )
    set "APK_PATH=!PASTE!"
)

for %%A in ("%APK_PATH%") do set APK_SIZE=%%~zA
set /a APK_SIZE_MB=APK_SIZE/1024/1024
echo        大小约: !APK_SIZE_MB! MB
echo.

:: -------------------- 2. 找 ADB --------------------
echo [2/5] 检查 ADB 环境...
where adb >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    set "CANDIDATE_ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    if exist "!CANDIDATE_ADB!" (
        echo [INFO] 已找到 Android Studio 自带 ADB
        set "ADB=!CANDIDATE_ADB!"
    ) else (
        echo ❌ 找不到 ADB，请先安装 Android Studio 或 platform-tools
        pause
        exit /b 2
    )
) else (
    set "ADB=adb"
)
echo [OK] ADB 就绪
echo.

:: -------------------- 3. 查手机 --------------------
echo [3/5] 检查手机 USB 连接...
"%ADB%" devices > nul
"%ADB%" get-state >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo ❌ 没有检测到手机！请插好数据线并开启 USB 调试（开发者选项）
    pause
    exit /b 3
)
for /f "delims=" %%A in ('"%ADB%" shell getprop ro.product.brand 2^>nul') do set BRAND=%%A
for /f "delims=" %%A in ('"%ADB%" shell getprop ro.product.model 2^>nul') do set MODEL=%%A
echo [OK] 手机已连接: !BRAND! !MODEL!
echo.

:: -------------------- 4. 安装 + 启动 --------------------
echo [4/5] 安装 APK 并启动 App...
"%ADB%" install -r "%APK_PATH%"
if %ERRORLEVEL% NEQ 0 (
    echo ❌ 安装失败！请先手动卸载手机上同包名旧版本再重试
    pause
    exit /b 4
)
"%ADB%" shell am start -n com.honor.appblocker/.MainActivity >nul 2>nul
echo [OK] App 已安装并启动
echo.

:: -------------------- 5. 激活 DeviceOwner --------------------
echo [5/5] 激活 DeviceOwner（任何渠道禁装 APP）...
"%ADB%" shell dpm set-device-owner com.honor.appblocker/.AdminReceiver
echo.
if %ERRORLEVEL% EQU 0 (
    echo ✅ DeviceOwner 激活成功！无法再安装任何 APP/游戏
) else (
    echo ⚠️  DeviceOwner 激活失败（常见原因）：
    echo    ① 手机有 Google 账号 → 设置 - 用户和账号 - Google - 移除
    echo    ② 手机有 多用户/访客/隐私空间 → 去设置里删除
    echo    ③ 已激活过其他 DeviceOwner → 恢复出厂设置
    echo  【影响】仅禁装 APP 不生效；微信/抖音/4399游戏拦截仍然可用
    echo  【修复后重试】重新双击本脚本即可（不会重复安装）
)

echo.
echo ===========================================================
echo   🎉 安装流程结束，现在请在手机上做最后 1 件事：
echo ===========================================================
echo.
echo   打开 App → 点【🚀 一键启动全防护】→ 按提示手动开启【无障碍服务】
echo   （这一步 Android 强制必须手动，无法自动化）
echo.
echo   之后：手机管家 - 应用启动管理 → 荣耀应用管控 → 手动管理 → 3 项全勾
echo.
pause
endlocal
