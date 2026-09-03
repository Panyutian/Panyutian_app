@echo off
chcp 65001 > nul
title ============================================
title  荣耀应用管控 - 一键打包编译+安装+激活DeviceOwner
title ============================================
setlocal enabledelayedexpansion

:: ===== 脚本位置：HonorAppBlocker/scripts/一键打包并安装到手机.bat =====
:: 功能：
::   1. 检查手机连接（ADB）
::   2. Gradle 编译 Debug APK（gradlew assembleDebug）
::   3. ADB 安装 APK 到手机
::   4. 自动启动 App
::   5. 自动激活 DeviceOwner（禁装 APP 必开）
:: ===== 前置准备 =====
::   - 电脑已装 Android Studio 或 JDK 17+
::   - 电脑已装 ADB（platform-tools 已配 PATH，或 Android Studio 自带）
::   - 手机已开 USB 调试（开发者选项里开），并允许「USB 调试」弹窗
::   - 手机已移除 Google 账号、已关闭多用户/隐私空间（DeviceOwner 激活必备）

echo.
echo ===========================================================
echo   🚀 荣耀应用管控 - 一键打包+安装+激活DeviceOwner 一条龙
echo ===========================================================
echo.

:: -------------------- 0. 切到项目根目录 --------------------
cd /d "%~dp0\.."
set "PROJECT_DIR=%cd%"
echo [INFO] 项目目录: %PROJECT_DIR%
echo.

:: -------------------- 1. 检查并配置 ADB --------------------
echo [1/6] 检查 ADB 环境...
where adb >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    :: 没在 PATH 里，尝试用 Android Studio 自带的 SDK/platform-tools
    set "CANDIDATE_ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    if exist "!CANDIDATE_ADB!" (
        echo [INFO] 已找到 Android Studio 自带 ADB: !CANDIDATE_ADB!
        set "ADB=!CANDIDATE_ADB!"
    ) else (
        echo ❌ 找不到 ADB，请先安装 Android Studio 或 platform-tools 并配 PATH
        echo   下载地址: https://developer.android.com/studio/releases/platform-tools
        pause
        exit /b 1
    )
) else (
    set "ADB=adb"
)
echo [OK] ADB 就绪: %ADB%
echo.

:: -------------------- 2. 检查手机连接 --------------------
echo [2/6] 检查手机 USB 连接...
"%ADB%" devices
echo.
"%ADB%" get-state >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo ❌ 没有检测到手机！请检查：
    echo    1. 数据线是否插好
    echo    2. 手机是否开启了「USB 调试」（设置-开发者选项）
    echo    3. 手机屏幕是否亮起，是否点了「允许 USB 调试」
    pause
    exit /b 2
)
echo [OK] 手机已连接
for /f "delims=" %%A in ('"%ADB%" shell getprop ro.product.brand 2^>nul') do set BRAND=%%A
for /f "delims=" %%A in ('"%ADB%" shell getprop ro.product.model 2^>nul') do set MODEL=%%A
echo        设备: !BRAND! !MODEL!
echo.

:: -------------------- 3. Gradle 编译 Debug APK --------------------
echo [3/6] 开始编译 APK（Gradle assembleDebug，首次可能需下载依赖 5~15 分钟）...
if exist "gradlew.bat" (
    call gradlew.bat assembleDebug --no-daemon 2>&1
) else (
    :: 没有 gradlew 时尝试用系统 gradle
    where gradle >nul 2>nul
    if %ERRORLEVEL% NEQ 0 (
        echo ❌ 当前目录没有 gradlew.bat，且系统未装 Gradle
        echo   请用 Android Studio 打开本项目（会自动下载 Gradle Wrapper）再运行本脚本
        pause
        exit /b 3
    )
    call gradle assembleDebug --no-daemon 2>&1
)
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ❌ 编译失败！请把上面报错信息复制出来查看，常见问题：
    echo    1. 网络未翻墙导致无法下载 Google/Maven 依赖
    echo    2. JDK 版本过低（需要 JDK 17+，Android Studio 自带即可）
    pause
    exit /b 4
)
set "APK_PATH=app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK_PATH%" (
    echo ❌ 编译成功但没找到 APK: %APK_PATH%
    pause
    exit /b 5
)
echo [OK] 编译完成，APK 位置: %APK_PATH%
for %%A in ("%APK_PATH%") do set APK_SIZE=%%~zA
set /a APK_SIZE_MB=APK_SIZE/1024/1024
echo        大小约: !APK_SIZE_MB! MB
echo.

:: -------------------- 4. ADB 安装 APK --------------------
echo [4/6] 安装 APK 到手机（如已安装旧版本会自动覆盖）...
"%ADB%" install -r "%APK_PATH%"
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ❌ 安装失败！常见原因：
    echo    1. 手机上已存在同包名但签名不同的 App（请先手动卸载再试）
    echo    2. 手机存储空间不足
    echo    3. 手机上禁止了电脑通过 USB 安装应用（设置里允许）
    pause
    exit /b 6
)
echo [OK] 安装成功
echo.

:: -------------------- 5. 自动启动 App --------------------
echo [5/6] 启动 App...
"%ADB%" shell am start -n com.honor.appblocker/.MainActivity >nul 2>nul
echo [OK] App 已启动，请查看手机屏幕
echo.

:: -------------------- 6. 激活 DeviceOwner --------------------
echo [6/6] 尝试激活 DeviceOwner（禁装 APP 必需）...
"%ADB%" shell dpm set-device-owner com.honor.appblocker/.AdminReceiver
set "DO_RESULT=%ERRORLEVEL%"
echo.
if %DO_RESULT% EQU 0 (
    echo ✅ DeviceOwner 激活成功！从此任何渠道都无法安装新 APP/游戏
) else (
    echo.
    echo ⚠️  DeviceOwner 激活失败（这是最常见的一个卡点），请按以下排查：
    echo ----------------------------------------------------------------
    echo  错误原因（按可能性排序）：
    echo    1. 手机里有【Google 账号】→ 请删除：设置 - 用户和账号 - Google - 移除账号
    echo    2. 手机里有【多用户 / 访客 / 隐私空间】→ 请删除：设置 - 隐私 - 隐私空间
    echo    3. 手机里有【企业账号 / Work Profile】→ 请移除
    echo    4. 已激活过其他 DeviceOwner → 只能恢复出厂设置后重来
    echo    5. 系统设置里未允许「USB 调试（安全设置）」→ 开发者选项里打开
    echo ----------------------------------------------------------------
    echo.
    echo  ℹ️  即使这步失败，也不影响微信/抖音/4399游戏拦截；只是无法禁止安装新应用。
    echo  ℹ️  解决上述问题后，可直接双击运行【一键激活DeviceOwner仅激活.bat】仅重试这一步。
    echo.
)

:: -------------------- 完成 --------------------
echo.
echo ===========================================================
echo   🎉 全部流程结束，现在在手机上完成最后一步手动操作：
echo ===========================================================
echo.
echo   1. 手机上打开 App（如果刚才已弹出来就不用了）
echo   2. 点顶部【🚀 一键启动全防护】绿色大按钮
echo   3. 按弹窗提示跳转到【设置 - 无障碍 - 荣耀应用管控】
echo      → 打开【使用荣耀应用管控】开关 → 确定 → 返回 App
echo   4. （必做防杀后台）手机管家 - 应用启动管理
echo      → 把【荣耀应用管控】改为【手动管理】→ 3 项全部打勾
echo.
echo   完成后 App 内会显示：① ② ③ 步进度全部 ✅ 绿色 = 全部生效
echo.
pause
endlocal
