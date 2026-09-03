#Requires -Version 5
<#
.SYNOPSIS
    荣耀手机应用管控 - DeviceOwner 激活脚本 (PowerShell增强版)
.DESCRIPTION
    自动定位ADB -> 检查USB连接 -> 安装APK -> 激活DeviceOwner -> 验证策略
    针对荣耀 MagicUI / 华为 HarmonyOS 优化
.PACKAGE
    com.honor.appblocker
.ADMIN_RECEIVER
    com.honor.appblocker/.AdminReceiver
.EXAMPLE
    # 直接运行
    .\02_激活DeviceOwner.ps1
    # 以管理员运行（推荐，自动安装USB驱动提示）
    Start-Process powershell -Verb RunAs -ArgumentList "-ExecutionPolicy Bypass -File `"$PSCommandPath`""
#>
[CmdletBinding()]
param(
    [string]$ApkPath = "",   # 自定义APK路径（可选）
    [string]$ExtraAdbDir = "" # 自定义ADB目录（可选）
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# ========== 常量 ==========
$PKG_NAME     = 'com.honor.appblocker'
$ADMIN_COMP   = "$PKG_NAME/.AdminReceiver"
$SCRIPT_DIR   = Split-Path -Parent $MyInvocation.MyCommand.Path
$PROJECT_ROOT = Split-Path -Parent $SCRIPT_DIR
$SUCCESS_FLAG = $false

# ========== 颜色输出辅助 ==========
function Write-Color {
    param(
        [string]$Text,
        [ConsoleColor]$Color = [ConsoleColor]::White,
        [switch]$NoNewline
    )
    Write-Host $Text -ForegroundColor $Color -NoNewline:$NoNewline
}
function Step($n, $total, $msg) { Write-Color "`n[$n/$total] " -Color Cyan -NoNewline; Write-Color $msg -Color Yellow }
function Ok($msg)      { Write-Color "  [OK] " -Color Green -NoNewline; Write-Color $msg }
function Warn($msg)    { Write-Color "  [!] " -Color Yellow -NoNewline; Write-Color $msg -Color Yellow }
function Err($msg)     { Write-Color "  [X] " -Color Red -NoNewline; Write-Color $msg -Color Red }
function Info($msg)    { Write-Color "  [i] " -Color Gray -NoNewline; Write-Color $msg -Color Gray }
function Title($t) {
    Write-Host ""
    $line = "=" * 60
    Write-Color $line -Color Magenta
    Write-Color "  $t" -Color Magenta
    Write-Color $line -Color Magenta
    Write-Host ""
}

# ========== 启动页 ==========
Clear-Host
Title "荣耀应用管控 - DeviceOwner 激活工具 v1.0 (PowerShell)"

Write-Color "请先在手机端完成以下准备：`n" -Color White
$steps = @(
    '设置 → 关于手机 → 连续点「版本号」7次（开启开发者模式）',
    '设置 → 系统和更新 → 开发者选项 → 开启「USB调试」+「USB安装」',
    '设置 → 用户和账号 → 移除所有 Google 账号（必须，否则激活失败）',
    '设置 → 隐私 → 删除「隐私空间」；关闭「多用户」「访客」',
    '数据线连接电脑；手机USB用途选「传输文件(MTP)」（非仅充电/非MIDI）',
    '手机弹窗「允许USB调试」→ 勾选「始终允许」→ 点击「允许」'
)
for ($i=0; $i -lt $steps.Count; $i++) {
    Write-Color ("    {0}. " -f ($i+1)) -Color DarkCyan -NoNewline
    Write-Color $steps[$i] -Color Gray
}
Write-Host ""
Write-Color "确认已完成全部步骤？按 Enter 继续，Ctrl+C 退出..." -Color DarkYellow
$null = $Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown')

# ============================================================
# 步骤 1/6 定位 ADB
# ============================================================
Step 1 6 "定位 ADB 工具"

$adbCandidates = @()
# 1) -ExtraAdbDir 参数
if ($ExtraAdbDir) { $adbCandidates += Join-Path $ExtraAdbDir 'adb.exe' }
# 2) PATH 查找
try {
    $pathAdb = (Get-Command adb.exe -ErrorAction Stop).Source
    if ($pathAdb) { $adbCandidates += $pathAdb }
} catch {}
# 3) 常见SDK位置
@(
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    "$env:ProgramFiles\Android\platform-tools\adb.exe",
    "C:\Android\platform-tools\adb.exe",
    "D:\Android\platform-tools\adb.exe",
    "C:\adb\adb.exe", "D:\adb\adb.exe",
    "$SCRIPT_DIR\adb\adb.exe", "$SCRIPT_DIR\platform-tools\adb.exe",
    "$PROJECT_ROOT\adb\adb.exe"
) | ForEach-Object { $adbCandidates += $_ }

$ADB = $null
foreach ($c in $adbCandidates) {
    if (Test-Path $c) { $ADB = (Resolve-Path $c).Path; break }
}
if (-not $ADB) {
    Err "未找到 adb.exe！"
    Write-Color "  请下载 Android Platform Tools：" -Color Red
    Write-Color "  https://dl.google.com/android/repository/platform-tools-latest-windows.zip" -Color Cyan
    Write-Color "  解压后把整个 platform-tools 目录加进 PATH，或把 adb.exe + AdbWinApi.dll + AdbWinUsbApi.dll" -Color Red
    Write-Color "  放到本脚本目录下的 adb\ 文件夹。`n" -Color Red
    exit 1
}
Ok "ADB 路径: $ADB"

function Invoke-Adb {
    param([string]$Args, [switch]$Raw, [switch]$IgnoreExit)
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $ADB
    $psi.Arguments = $Args
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $out = $p.StandardOutput.ReadToEnd()
    $err = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    if (-not $IgnoreExit -and $p.ExitCode -ne 0 -and $err) {
        Write-Color ("  [adb $Args] " + $err.Trim()) -Color DarkRed
    }
    if ($Raw) { return [pscustomobject]@{ ExitCode=$p.ExitCode; Out=$out; Err=$err } }
    return ($out + $err).Trim()
}

# ============================================================
# 步骤 2/6 设备连接检查
# ============================================================
Step 2 6 "检查手机 USB 连接与授权"

# 辅助：检查Windows层USB设备
try {
    $usbDevs = Get-PnpDevice -Class AndroidUsbDevice -Status OK -ErrorAction SilentlyContinue
    $usbDevs2 = Get-PnpDevice -Class USB -ErrorAction SilentlyContinue | Where-Object {
        $_.FriendlyName -match 'Android|荣耀|Honor|华为|Huawei|ADB|HDB|MTP' -and $_.Status -eq 'OK'
    }
    if ($usbDevs -or $usbDevs2) {
        Ok "Windows 已识别到手机 USB 设备"
        @($usbDevs.FriendlyName) + @($usbDevs2.FriendlyName) | Select-Object -Unique | Where-Object { $_ } | ForEach-Object {
            Info "  → $_"
        }
    } else {
        Warn "Windows未识别Android设备，若连接失败请安装荣耀手机驱动"
    }
} catch {}

# 重启ADB服务
Invoke-Adb 'kill-server' | Out-Null
Start-Sleep -Milliseconds 800
Invoke-Adb 'start-server' | Out-Null
Start-Sleep -Seconds 2

$devRaw = (Invoke-Adb 'devices' -Raw).Out
$lines = $devRaw -split "`r?`n" | Where-Object { $_ -and $_ -notmatch 'List of devices' }
if (-not $lines -or $lines.Count -eq 0) {
    Err "未检测到任何设备！"
    Write-Color "  排查：`n" -Color Red
    Write-Color "    · 数据线是否是数据线（有数据针脚），不是仅充电线？`n" -Color Yellow
    Write-Color "    · 通知栏 → USB 用途 → 是否为「传输文件(MTP)」？`n" -Color Yellow
    Write-Color "    · 开发者选项中「USB调试」是否已开启？`n" -Color Yellow
    Write-Color "    · 设备管理器（devmgmt.msc）中 Android 设备是否带黄色感叹号？装荣耀驱动`n" -Color Yellow
    exit 2
}
if ($lines.Count -gt 1) { Warn "检测到多台设备，仅操作第一台。请在需要操作的设备上执行！" }

$dev0 = $lines[0] -split '\s+'
$serial, $state = $dev0[0], $dev0[1]
Info "设备序列号: $serial   当前状态: $state"

switch -Exact ($state) {
    'unauthorized' {
        Err "设备未授权！"
        Write-Color "  → 请查看手机屏幕，点击「允许USB调试」并勾选「始终允许此计算机」，然后重跑。`n" -Color Yellow
        exit 3
    }
    'offline' {
        Err "设备离线！请拔插数据线或在开发者选项中「撤销USB调试授权」后重连。`n"
        exit 4
    }
    'device' { Ok "设备已连接并授权" }
    default {
        Err "未知状态 $state，请排查后重试。`n"
        exit 5
    }
}

# 读取设备信息
$brand    = (Invoke-Adb 'shell getprop ro.product.brand').Trim()
$model    = (Invoke-Adb 'shell getprop ro.product.model').Trim()
$androidV = (Invoke-Adb 'shell getprop ro.build.version.release').Trim()
$magicUi  = (Invoke-Adb 'shell getprop ro.build.version.emui').Trim()
if (-not $magicUi) { $magicUi = (Invoke-Adb 'shell getprop ro.huawei.build.display.id').Trim() }
Write-Color "  品牌:$brand  型号:$model  Android:$androidV  MagicUI/HarmonyOS:$magicUi" -Color Gray

# ============================================================
# 步骤 3/6 安装APK
# ============================================================
Step 3 6 "检查并安装 APK (com.honor.appblocker)"

$pkgInstalled = (Invoke-Adb "shell pm list packages $PKG_NAME") -match "package:$PKG_NAME"
if ($pkgInstalled) {
    $ver = (Invoke-Adb "shell dumpsys package $PKG_NAME" | Select-String 'versionName=(\S+)' | ForEach-Object { $_.Matches[0].Groups[1].Value })
    Ok "APK 已安装，版本 $ver，跳过安装。"
} else {
    # 搜索APK
    $apk = $null
    if ($ApkPath -and (Test-Path $ApkPath)) { $apk = (Resolve-Path $ApkPath).Path }
    if (-not $apk) {
        $searchPaths = @(
            $PROJECT_ROOT,
            "$PROJECT_ROOT\app\build\outputs\apk\release",
            "$PROJECT_ROOT\app\build\outputs\apk\debug",
            $SCRIPT_DIR,
            "$env:USERPROFILE\Desktop"
        )
        foreach ($dir in $searchPaths) {
            if (-not (Test-Path $dir)) { continue }
            $found = Get-ChildItem -Path $dir -Filter *.apk -File -Recurse -Depth 3 -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime -Descending | Select-Object -First 5 |
                Where-Object { $_.Name -match 'app-release|app-debug|Honor' }
            if ($found) { $apk = $found[0].FullName; break }
        }
    }
    if (-not $apk) {
        Err "没有找到APK文件！请先编译Android项目或把APK放到脚本目录。"
        exit 6
    }
    Info "将安装: $apk"
    Write-Color "  正在通过ADB安装... 荣耀手机若弹出风险提示，请点击「允许本次安装」" -Color DarkYellow
    $r = Invoke-Adb "install -r `"$apk`"" -Raw
    if ($r.Out -match 'Success') {
        Ok "APK 安装成功"
    } else {
        Warn "ADB安装失败。荣耀系统可能拦截了USB安装。请："
        Write-Color "    ① 关闭开发者选项中的「USB安装验证」" -Color Yellow
        Write-Color "    ② 或把APK复制到手机手动安装：adb push `"$apk`" /sdcard/ → 文件管理器点击安装" -Color Yellow
        $choice = Read-Host "  已手动完成安装？(Y=继续 / N=退出)"
        if ($choice -notmatch '^[Yy]') { exit 7 }
        # 再次检查
        Start-Sleep 2
        if (-not ((Invoke-Adb "shell pm list packages $PKG_NAME") -match "package:$PKG_NAME")) {
            Err "仍未检测到APK，退出。"
            exit 8
        }
    }
}

# ============================================================
# 步骤 4/6 前置环境检查
# ============================================================
Step 4 6 "DeviceOwner 前置条件检查"

# 检查多用户/用户数量
$users = Invoke-Adb 'shell pm list users'
Info "当前用户列表："
($users -split "`r?`n" | Where-Object { $_ }) | ForEach-Object { Write-Color ("      " + $_.Trim()) -Color Gray }
$userCount = ($users -split "`r?`n" | Where-Object { $_ -match 'UserInfo\{[0-9]+:' }).Count
if ($userCount -gt 1) {
    Warn "检测到 $userCount 个用户！DeviceOwner 要求仅一个主用户。必须删除所有访客/隐私空间。"
} else {
    Ok "用户数 = $userCount（符合要求）"
}

# 检查当前 DeviceOwner
$dumpsysPolicy = Invoke-Adb 'shell dumpsys device_policy'
$curOwnerMatch = [regex]::Match($dumpsysPolicy, 'Device Owner:\s*ComponentInfo\{([^/]+)/([^}]+)\}')
if ($curOwnerMatch.Success) {
    if ($curOwnerMatch.Groups[1].Value -eq $PKG_NAME) {
        Ok "当前 DeviceOwner 已是本应用"
    } else {
        $curOwner = "$($curOwnerMatch.Groups[1].Value)/$($curOwnerMatch.Groups[2].Value)"
        Warn "当前已有其他 DeviceOwner：$curOwner"
        Warn "必须先解除该 DeviceOwner 或恢复出厂设置，否则本应用无法设置。"
        $choice = Read-Host "  是否仍尝试继续？(Y/N)"
        if ($choice -notmatch '^[Yy]') { exit 9 }
    }
} else {
    Ok "当前无 DeviceOwner，可以设置。"
}

# 检查账号（尽力而为）
$acc = Invoke-Adb 'shell dumpsys account' 2>$null
$googleAccs = ([regex]::Matches($acc, 'Account \{[^}]*name=[^:]+:.google\.com[^}]*\}')).Count
if ($googleAccs -gt 0) {
    Warn "检测到可能有 $googleAccs 个 Google 账号！DeviceOwner 激活前必须全部移除。"
} else {
    Ok "未检测到 Google 账号（正常）"
}

# ============================================================
# 步骤 5/6 核心：激活 DeviceOwner
# ============================================================
Step 5 6 "执行 DeviceOwner 激活（核心命令）"

Write-Host ""
Write-Color "  执行命令：adb shell dpm set-device-owner $ADMIN_COMP" -Color Cyan
Write-Color (" " + "-" * 70) -Color DarkCyan
$r = Invoke-Adb "shell dpm set-device-owner $ADMIN_COMP" -Raw
Write-Host ""
if ($r.Out) { ($r.Out -split "`r?`n") | ForEach-Object { Write-Color ("    " + $_.Trim()) -Color White } }
if ($r.Err) { ($r.Err -split "`r?`n") | ForEach-Object { Write-Color ("    " + $_.Trim()) -Color Red } }
Write-Color (" " + "-" * 70) -Color DarkCyan
Write-Host ""

$SUCCESS_FLAG = ($r.Out -match 'Success:')
if (-not $SUCCESS_FLAG) {
    Title "激活失败 - 错误原因分析"
    $errText = "$($r.Out) $($r.Err)"
    switch -Regex ($errText) {
        'already several users' {
            Err "原因：手机存在多用户 / 访客 / 隐私空间"
            Write-Color "  解决: 设置→隐私→删除隐私空间；设置→用户和账号→移除所有访客用户。" -Color Yellow
            Write-Color "        若仍不行，必须：备份数据→恢复出厂→开机不连WiFi不登录账号→安装APK→立即激活" -Color Yellow
        }
        'accounts on the device' {
            Err "原因：手机存在 Google/企业 账号"
            Write-Color "  解决: 设置→用户和账号→移除所有 Google 账号、公司账号后重跑。" -Color Yellow
        }
        'unknown admin|unknown package|No active admin' {
            Err "原因：接收器/包名不匹配或APK未正确安装"
            Write-Color "  解决: 卸载旧APK→重新编译安装→确认 AndroidManifest 中 AdminReceiver 组件声明存在。" -Color Yellow
        }
        'BIND_DEVICE_ADMIN|Not allowed to.*(provisioned|owned user)' {
            Err "原因：Android 14+ 新限制 / 设备已完成Setup Wizard"
            Write-Color "  解决: 恢复出厂设置，开机进入Welcome页时连电脑用ADB激活（必须在向导完成前）。" -Color Yellow
            Write-Color "        或使用「二维码零-touch配置」方法在开机向导阶段扫码配置DeviceOwner。" -Color Yellow
        }
        'MANAGED_PROVISIONING|admin cannot be added' {
            Err "原因：ROM 限制或DevicePolicy组件异常"
            Write-Color "  解决: 重启手机后重试；或备份数据→恢复出厂设置后不登录账号再激活。" -Color Yellow
        }
        default {
            Err "未知错误。请把上方输出发给开发者排查。"
        }
    }
    Write-Host ""
    Read-Host "按 Enter 退出"
    exit 10
}

# ============================================================
# 步骤 6/6 验证管控策略
# ============================================================
Step 6 6 "验证管控策略"

# 再次dumpsys验证
$dumpsysPolicy = Invoke-Adb 'shell dumpsys device_policy'
$ownerOK = [regex]::Match($dumpsysPolicy, 'Device Owner:\s*ComponentInfo\{com\.honor\.appblocker/')
$restrictOK = [regex]::Matches($dumpsysPolicy, 'disallow_install_apps[=:]\s*true').Count

if ($ownerOK.Success) {
    Ok "Device Owner 验证成功：组件 = com.honor.appblocker.AdminReceiver"
} else { Warn "dumpsys 未读取到 Device Owner 信息，但返回 Success，请在手机APP内确认。" }

if ($restrictOK -ge 1) {
    Ok "已启用限制：DISALLOW_INSTALL_APPS = true（禁止安装任何应用）"
} else {
    Warn "未读取到限制信息（可能在设置策略前读取），请打开手机APP的主界面确认。"
}

# 触发应用内策略补全（打开一次APP，使AdminReceiver.onEnabled重入）
Invoke-Adb "shell monkey -p $PKG_NAME -c android.intent.category.LAUNCHER 1" | Out-Null
Start-Sleep -Milliseconds 500
# 再HOME
Invoke-Adb 'shell input keyevent KEYCODE_HOME' | Out-Null

# ========== 完成 ==========
Title "✅ DeviceOwner 激活成功！"
Write-Color "`n  管控功能已生效，现在可拔下数据线。建议在手机端执行以下验证：" -Color Green
Write-Color "`n    1. 打开「荣耀应用管控」→ 顶部 DeviceOwner 状态应显示 「✅ 已获得」" -Color White
Write-Color "    2. 去应用市场/浏览器下载任意APP → 安装按钮应被系统阻止" -Color White
Write-Color "    3. 打开微信 → 发现 → 游戏 → 应立即自动返回桌面（需开启无障碍）" -Color White
Write-Color "    4. 开启无障碍服务后，弹窗游戏广告会自动关闭" -Color White

Write-Color "`n  如要卸载本应用，请先在APP内点「解除DeviceOwner」按钮，或执行脚本：" -Color Gray
Write-Color "     scripts\解除DeviceOwner.ps1`n" -Color Cyan
Read-Host "按 Enter 退出脚本"
exit 0
