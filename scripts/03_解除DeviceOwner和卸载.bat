@echo off
chcp 65001 >nul
title 解除DeviceOwner - 荣耀应用管控
color 0E

echo.
echo  ============================================================
echo     荣耀应用管控 - 解除 DeviceOwner / 卸载辅助工具
echo  ============================================================
echo.
echo  用途：
echo    本脚本可执行以下操作（按序号选择）：
echo    1. 解除 DeviceOwner 并移除管理员权限 → 可以卸载应用
echo    2. 仅解除管理员权限（非DeviceOwner时）
echo    3. 强制卸载 APK（root或ADB特权）
echo    4. 查看当前 DeviceOwner / 管理员状态
echo.
echo  ============================================================
echo.

:: 查找ADB
set "ADB_PATH="
where adb.exe >nul 2>&1 && for /f "delims=" %%i in ('where adb.exe 2^>nul') do set "ADB_PATH=%%i" & goto :chk
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB_PATH=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" & goto :chk
if exist "%~dp0adb\adb.exe" set "ADB_PATH=%~dp0adb\adb.exe" & goto :chk
:chk
if not defined ADB_PATH (
    echo [错误] 未找到adb.exe，请先安装Android Platform Tools。
    pause
    exit /b 1
)
echo [OK] ADB: %ADB_PATH%

echo.
echo ---------- 当前设备状态 ----------
"%ADB_PATH%" devices
echo.
echo ---------- 当前 DeviceOwner / 管理员 ----------
"%ADB_PATH%" shell dumpsys device_policy 2>nul | findstr /i /c:"Device Owner" /c:"active-admin" /c:"AdminReceiver" /c:"flattenToString"

echo.
echo.
echo 请选择操作：
echo   [1] 解除 DeviceOwner + 管理员（推荐）
echo   [2] 仅移除管理员 (remove-active-admin)
echo   [3] 强制卸载应用
echo   [4] 刷新状态信息
echo   [0] 退出
echo.
set /p "OPT=输入数字后回车: "

if "%OPT%"=="1" goto :REMOVE_OWNER
if "%OPT%"=="2" goto :REMOVE_ADMIN
if "%OPT%"=="3" goto :UNINSTALL
if "%OPT%"=="4" "%~f0"
if "%OPT%"=="0" goto :EOF

echo 无效选项: %OPT%
pause
exit /b

:REMOVE_OWNER
echo.
echo [方法1] 尝试通过 dumpsys device_policy 清除 DeviceOwner
echo 命令: adb shell dpm clear-device-owner
"%ADB_PATH%" shell dpm clear-device-owner com.honor.appblocker
if %errorlevel%==0 (
    echo [OK] clear-device-owner 执行成功
) else (
    echo [跳过] clear-device-owner 失败（可能需要Android 8+或直接解除）
)
echo.
echo [方法2] 移除管理员 remove-active-admin
echo 命令: adb shell dpm remove-active-admin com.honor.appblocker/.AdminReceiver
"%ADB_PATH%" shell dpm remove-active-admin com.honor.appblocker/.AdminReceiver
echo.
echo [状态] 验证：
"%ADB_PATH%" shell dumpsys device_policy 2>nul | findstr /i "Device Owner: active-admin"
echo.
echo ============================================================
echo  若上方 dumpsys 中没有 Device Owner 和 admin 信息 → 解除成功！
echo  现在可以在 设置→应用→荣耀应用管控 中点击「卸载」。
echo ============================================================
echo.
pause
goto :EOF

:REMOVE_ADMIN
echo.
echo 执行: adb shell dpm remove-active-admin com.honor.appblocker/.AdminReceiver
"%ADB_PATH%" shell dpm remove-active-admin com.honor.appblocker/.AdminReceiver
echo.
pause
goto :EOF

:UNINSTALL
echo.
echo 注意：若 DeviceOwner 未解除，卸载会失败！建议先执行选项[1]。
echo 是否继续卸载？(Y/N)
set /p "A="
if /i not "%A%"=="Y" goto :EOF
echo.
echo [尝试1] 普通卸载
"%ADB_PATH%" uninstall com.honor.appblocker
echo.
echo [尝试2] shell pm uninstall
"%ADB_PATH%" shell pm uninstall -k --user 0 com.honor.appblocker
echo.
pause
goto :EOF
