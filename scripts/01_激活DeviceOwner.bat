@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title 荣耀应用管控 - DeviceOwner激活工具 (CMD版)
color 0A

:: ============================================================
:: 荣耀手机应用管控 - DeviceOwner 一键激活脚本
:: 功能：自动定位ADB → 检查设备连接 → 安装APK → 设置DeviceOwner
:: 包名：com.honor.appblocker
:: 核心命令：adb shell dpm set-device-owner com.honor.appblocker/.AdminReceiver
:: ============================================================

echo.
echo  ============================================================
echo     荣耀应用管控 - DeviceOwner 一键激活工具
echo  ============================================================
echo.
echo  请务必先完成以下准备工作：
echo    [1] 手机：设置 → 关于手机 → 连续点击版本号7次 (开启开发者模式)
echo    [2] 手机：设置 → 系统和更新 → 开发者选项 → 打开「USB调试」
echo    [3] 手机：设置 → 用户和账号 → 移除所有 Google 账号 (必须!)
echo    [4] 手机：关闭「多用户」「访客模式」「隐私空间」
echo    [5] 手机：用数据线连接电脑，USB用途选择「传输文件(MTP)」
echo    [6] 手机：屏幕弹出「允许USB调试」时，点击「允许」并勾选始终允许
echo.
echo  确认完成以上步骤后按任意键继续...
pause >nul

:: ============================================================
:: 第1步：定位 ADB.exe (多路径搜索)
:: ============================================================
cls
echo [步骤 1/6] 正在查找 ADB 工具...
set "ADB_PATH="

:: 1. 检查系统PATH
where adb.exe >nul 2>&1
if %errorlevel%==0 (
    for /f "delims=" %%i in ('where adb.exe 2^>nul') do (
        set "ADB_PATH=%%i"
        goto :found_adb
    )
)

:: 2. 常见Android SDK路径
set "SDK_PATHS=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "SDK_PATHS=%SDK_PATHS%;%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe"
set "SDK_PATHS=%SDK_PATHS%;C:\Android\platform-tools\adb.exe"
set "SDK_PATHS=%SDK_PATHS%;D:\Android\platform-tools\adb.exe"
set "SDK_PATHS=%SDK_PATHS%;C:\adb\adb.exe;D:\adb\adb.exe"
set "SDK_PATHS=%SDK_PATHS%;%~dp0adb\adb.exe;%~dp0platform-tools\adb.exe"

for %%P in (%SDK_PATHS%) do (
    if exist "%%~fP" (
        set "ADB_PATH=%%~fP"
        goto :found_adb
    )
)

:found_adb
if not defined ADB_PATH (
    echo.
    echo [错误] 未找到 adb.exe！
    echo 请安装 Android Platform Tools：
    echo   下载地址: https://dl.google.com/android/repository/platform-tools-latest-windows.zip
    echo   解压后将 platform-tools 目录加入系统环境变量 PATH
    echo   或把解压后的 adb.exe、AdbWinApi.dll、AdbWinUsbApi.dll 复制到本脚本同目录下的 adb\ 文件夹
    echo.
    pause
    exit /b 1
)
echo [成功] 找到 ADB: %ADB_PATH%

:: 取ADB所在目录，用于fastboot等辅助命令
for %%I in ("%ADB_PATH%") do set "ADB_DIR=%%~dpI"

:: ============================================================
:: 第2步：重启ADB服务 + 检测设备连接
:: ============================================================
echo.
echo [步骤 2/6] 正在检查手机连接状态...
"%ADB_PATH%" kill-server >nul 2>&1
timeout /t 1 /nobreak >nul
"%ADB_PATH%" start-server >nul 2>&1
timeout /t 2 /nobreak >nul

set "DEVICE_COUNT=0"
set "DEVICE_STATE="
for /f "skip=1 tokens=1,2" %%A in ('"%ADB_PATH%" devices 2^>nul') do (
    if not "%%B"=="" (
        set "DEVICE_STATE=%%B"
        set /a DEVICE_COUNT+=1
    )
)

if %DEVICE_COUNT%==0 (
    echo.
    echo [错误] 未检测到任何手机设备！
    echo 请检查：
    echo   1. 数据线是否连接正常
    echo   2. 手机是否弹出「允许USB调试」提示
    echo   3. USB用途是否为「传输文件(MTP)」，而非「仅充电」或「MIDI」
    echo   4. 设备管理器中是否有Android设备（带黄色感叹号需安装荣耀手机驱动）
    echo.
    pause
    exit /b 2
)

if %DEVICE_COUNT% gtr 1 (
    echo [警告] 检测到多台设备，将仅操作第一台。为避免错误，请只连接一台手机后重试。
)

if /i "%DEVICE_STATE%"=="unauthorized" (
    echo.
    echo [错误] 手机未授权USB调试！
    echo 请查看手机屏幕，点击「允许USB调试」并勾选「始终允许此电脑」
    echo 然后重新运行本脚本。
    echo.
    pause
    exit /b 3
)
if /i not "%DEVICE_STATE%"=="device" (
    echo.
    echo [错误] 设备连接异常: %DEVICE_STATE%
    echo 请重新拔插数据线、重启USB调试开关后重试。
    echo.
    pause
    exit /b 4
)

echo [成功] 设备已连接 (状态: %DEVICE_STATE%)
"%ADB_PATH%" shell getprop ro.product.brand 2>nul | set /p "BRAND="
"%ADB_PATH%" shell getprop ro.product.model 2>nul | set /p "MODEL="
"%ADB_PATH%" shell getprop ro.build.version.release 2>nul | set /p "ANDROID_VER="
echo        品牌: !BRAND!   型号: !MODEL!   Android: !ANDROID_VER!

:: ============================================================
:: 第3步：检查并安装APK
:: ============================================================
echo.
echo [步骤 3/6] 正在检查应用是否已安装...

:: 查找APK文件（同目录下优先找 release，其次 debug）
set "APK_FILE="
for %%F in (
    "%~dp0*.apk"
    "%~dp0app\build\outputs\apk\release\*.apk"
    "%~dp0app\build\outputs\apk\debug\*.apk"
    "%USERPROFILE%\Desktop\*.apk"
) do (
    for /f %%a in ('dir /b /o-d "%%~fF" 2^>nul ^| findstr /i "app-debug app-release Honor"') do (
        if not defined APK_FILE set "APK_FILE=%%~dpF%%a"
    )
)

set "PKG_INSTALLED=0"
"%ADB_PATH%" shell pm list packages com.honor.appblocker >nul 2>&1
if %errorlevel%==0 (
    for /f "delims=" %%L in ('"%ADB_PATH%" shell pm list packages com.honor.appblocker 2^>nul ^| findstr /x "package:com.honor.appblocker"') do (
        set "PKG_INSTALLED=1"
    )
)

if %PKG_INSTALLED%==1 (
    echo [信息] 应用 com.honor.appblocker 已安装，跳过安装。
) else (
    if not defined APK_FILE (
        echo.
        echo [警告] 未在常见位置找到 APK 安装包。
        echo 请先在 Android Studio 编译项目生成 APK，或把 app-release.apk 放到脚本同目录。
        echo 是否尝试继续（如果你是手动安装的或稍后安装）？(Y/N)
        set /p "ANS="
        if /i not "!ANS!"=="Y" (
            pause
            exit /b 5
        )
    ) else (
        echo [信息] 找到APK: !APK_FILE!
        echo        正在安装 (荣耀手机请在安装提示点击"继续安装")...
        "%ADB_PATH%" install -r "!APK_FILE!"
        if !errorlevel! neq 0 (
            echo.
            echo [警告] ADB安装失败，可能被荣耀安全校验拦截。
            echo 请尝试：
            echo   - 关闭开发者选项中的「USB安装验证」或「通过USB验证应用」
            echo   - 或直接把 APK 拷贝到手机存储手动安装，安装完成后回到本脚本
            echo.
            echo 手动安装完成后按任意键继续...
            pause >nul
        )
    )
)

:: ============================================================
:: 第4步：前置环境检查 (DeviceOwner必查项)
:: ============================================================
echo.
echo [步骤 4/6] 正在检查 DeviceOwner 激活前置条件...
set "WARN_COUNT=0"

:: 4.1 检查是否已安装
set "NOW_INSTALLED=0"
for /f "delims=" %%L in ('"%ADB_PATH%" shell pm list packages com.honor.appblocker 2^>nul ^| findstr /x "package:com.honor.appblocker"') do set "NOW_INSTALLED=1"
if !NOW_INSTALLED!==0 (
    echo   [错误] 应用仍未安装，无法继续。请先安装APK后重脚本。
    pause
    exit /b 6
)
echo   [OK] 应用 com.honor.appblocker 已安装。

:: 4.2 检查账号（无法直接看Google账号数量，但提示用户）
echo   [提示] 若之前未移除Google账号，请在手机上：设置 → 用户和账号 → 移除所有 Google 账号。

:: 4.3 检查多用户
for /f "delims=" %%L in ('"%ADB_PATH%" shell pm list users 2^>nul') do (
    echo   [信息] %%L
)

:: 4.4 检查当前是否已有DeviceOwner
set "CUR_OWNER="
for /f "delims=" %%L in ('"%ADB_PATH%" shell dumpsys device_policy 2^>nul ^| findstr /i "Device Owner:"') do (
    set "CUR_OWNER=%%L"
)
if defined CUR_OWNER (
    echo   [警告] 当前已有DeviceOwner: !CUR_OWNER!
    echo   若不是目标应用，必须先解除现有DeviceOwner或恢复出厂设置。
    echo.
    echo  是否仍继续尝试？(可能会失败) (Y/N)
    set /p "ANS2="
    if /i not "!ANS2!"=="Y" (
        pause
        exit /b 7
    )
)
echo   [OK] 前置检查完成。

:: ============================================================
:: 第5步：激活 DeviceOwner (核心命令)
:: ============================================================
echo.
echo [步骤 5/6] 正在激活 DeviceOwner (核心命令)...
echo.
echo  执行命令: adb shell dpm set-device-owner com.honor.appblocker/.AdminReceiver
echo ================================================================

set "CMD_OUTPUT="
for /f "delims=" %%O in ('"%ADB_PATH%" shell dpm set-device-owner com.honor.appblocker/.AdminReceiver 2^>^&1') do (
    echo  %%O
    set "CMD_OUTPUT=!CMD_OUTPUT! %%O"
)

echo ================================================================
echo.

:: 判断结果
echo !CMD_OUTPUT! | findstr /i "Success:" >nul
if %errorlevel%==0 (
    echo [成功] DeviceOwner 已成功激活！🎉
    set "RESULT=OK"
) else (
    echo [失败] 激活失败，常见原因如下：
    echo.
    echo  错误信息: !CMD_OUTPUT!
    echo.
    echo  ┌─────────────────────────────────────────────────────────────┐
    echo  │ 错误: Not allowed to set device owner because there are      │
    echo  │        already several users on the device                   │
    echo  │ 原因: 手机有多个用户/访客/隐私空间                           │
    echo  │ 解决: 设置→隐私→删除隐私空间；设置→用户和账号→删除所有访客    │
    echo  │       如果仍然失败，只能：备份数据→恢复出厂设置→开机不登录账号 │
    echo  │       →不连WiFi→立即安装本APK→执行激活命令                   │
    echo  ├─────────────────────────────────────────────────────────────┤
    echo  │ 错误: Not allowed ... accounts on the device                 │
    echo  │ 原因: 手机上已登录Google账号或企业账号                        │
    echo  │ 解决: 设置→用户和账号→移除所有Google账号                     │
    echo  ├─────────────────────────────────────────────────────────────┤
    echo  │ 错误: No active admin ... or unknown package                │
    echo  │ 原因: APK未安装或包名/接收器名写错                            │
    echo  │ 解决: 确认APK已成功安装，包名为 com.honor.appblocker         │
    echo  ├─────────────────────────────────────────────────────────────┤
    echo  │ 错误: Neither user 2000 nor current process has             │
    echo  │        android.permission.BIND_DEVICE_ADMIN                  │
    echo  │ 原因: Android 14+ 安全限制，设备被Provisioned后无法设DeviceOwner│
    echo  │ 解决: 只能恢复出厂设置→SetupWizard前用ADB设，或使用扫码配置法  │
    echo  └─────────────────────────────────────────────────────────────┘
    set "RESULT=FAIL"
)

:: ============================================================
:: 第6步：验证结果
:: ============================================================
echo.
echo [步骤 6/6] 正在验证管控策略...
if "%RESULT%"=="OK" (
    echo.
    echo  --- 验证信息 ---
    "%ADB_PATH%" shell dumpsys device_policy 2>nul | findstr /i "Device Owner: AdminReceiver android.app.action.DEVICE_ADMIN_ENABLED"
    "%ADB_PATH%" shell dumpsys device_policy 2>nul | findstr /i "disallow_install_apps: true" >nul && echo [OK] DISALLOW_INSTALL_APPS = true (已禁止安装APP)
    "%ADB_PATH%" shell dumpsys device_policy 2>nul | findstr /i "disallow_factory_reset: true" >nul && echo [OK] DISALLOW_FACTORY_RESET = true (已禁止恢复出厂)
    echo.
    echo ============================================================
    echo   ✅ 激活成功！管控功能已生效。
    echo ============================================================
    echo.
    echo   现在可以拔下数据线，打开手机上的「荣耀应用管控」进行验证：
    echo   1. DeviceOwner权限应显示为「✅ 已获得」
    echo   2. 尝试从应用市场安装任意APP → 应提示"无法安装"
    echo   3. 打开微信 → 发现 → 游戏 → 应自动返回桌面
    echo.
) else (
    echo ============================================================
    echo   ❌ 激活失败，请根据上方错误说明排查后重新运行本脚本。
    echo ============================================================
)

echo.
echo 按任意键退出...
pause >nul
endlocal
exit /b 0
