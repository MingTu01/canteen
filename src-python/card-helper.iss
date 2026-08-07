; =============================================================================
; 读卡助手 - Inno Setup 安装包脚本
; =============================================================================
; 用途:将 PyInstaller 产物(dist/card-helper/)打包为正式的 EXE 安装包
;     (把 OUR_IDR.dll 读卡器变成"模拟键盘输入+回车",开机静默自启)
;
; 安装包功能:
;   1. 安装读卡助手到 C:\Program Files\CanteenCardHelper\
;   2. 自动安装 CH375 读卡器驱动(调用 pnputil)
;   3. 注册 HKCU Run 开机自启(静默无窗口)
;   4. 提供完整的卸载程序
;
; 打包前置条件:
;   1. 已安装 Inno Setup 6+(https://jrsoftware.org/isdl.php)
;   2. 已运行 PyInstaller 生成 dist/card-helper/ 目录
;   3. 已将 CH375 驱动文件放入 src-python/drivers/ 目录
;
; 打包命令:
;   "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" card-helper.iss
;
; 产物:
;   output/CanteenCardHelper-Setup-<版本号>.exe
; =============================================================================
#define MyAppName "读卡助手"
#define MyAppNameEn "CanteenCardHelper"
#define MyAppVersion "1.2.0"
#define MyAppPublisher "Enterprise Canteen System"
#define MyAppExeName "card-helper.exe"

[Setup]
AppId={{8C2F9B31-2026-4F8D-9C5E-2CARDHELPER}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}

; 安装目录
DefaultDirName={autopf}\{#MyAppNameEn}
DefaultGroupName={#MyAppName}

; 输出配置
OutputDir=output
OutputBaseFilename=CanteenCardHelper-Setup-{#MyAppVersion}
Compression=lzma2/ultra64
SolidCompression=yes

; 兼容 Win7 SP1 及以上 32/64 位
ArchitecturesInstallIn64BitMode=x64compatible
ArchitecturesAllowed=x86compatible x64compatible

; 安装包外观
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\icon.ico
UninstallDisplayName={#MyAppName}

; 权限要求(需要管理员权限安装驱动)
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=dialog

; 安装向导语言
ShowLanguageDialog=no
LanguageDetectionMethod=none

[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Files]
; 主程序目录(PyInstaller onedir 产物,递归打包)
Source: "dist\card-helper\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

; 图标文件
Source: "icon.ico"; DestDir: "{app}"; Flags: ignoreversion

; CH375 驱动文件(只打包 INF/SYS/CAT/DLL,驱动安装由系统 pnputil 完成)
Source: "drivers\*"; DestDir: "{app}\drivers"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "SETUP.EXE,DRVSETUP64.exe"

[Registry]
; 开机静默自启(HKCU Run,无需管理员权限)
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; \
    ValueType: string; ValueName: "CanteenCardHelper"; \
    ValueData: """{app}\{#MyAppExeName}"""; \
    Flags: uninsdeletevalue

[Icons]
; 开始菜单快捷方式
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\icon.ico"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"; IconFilename: "{app}\icon.ico"

[Run]
; 安装 CH375 读卡器驱动 - 只使用系统自带 pnputil
; 先尝试新语法,失败则回退到旧语法,确保各版本 Windows 均可安装
Filename: "{cmd}"; Parameters: "/c pnputil /add-driver ""{app}\drivers\CH375WDM.INF"" /install 2>nul || pnputil -a ""{app}\drivers\CH375WDM.INF"""; \
    StatusMsg: "正在安装 CH375 读卡器驱动..."; \
    Flags: runhidden waituntilterminated

; 安装完成后自启动读卡助手
Filename: "{app}\{#MyAppExeName}"; Description: "启动读卡助手"; Flags: nowait skipifsilent

[UninstallDelete]
; 清理安装目录内残留的数据目录
Type: filesandordirs; Name: "{app}\data"
; 清理运行日志目录(程序把日志写到 %LOCALAPPDATA%\CanteenHelper)
Type: filesandordirs; Name: "{%LOCALAPPDATA}\CanteenHelper"

[Code]
// 安装初始化:关闭正在运行的读卡助手(覆盖安装时文件被占用会失败)
function InitializeSetup(): Boolean;
var
    ResultCode: Integer;
begin
    Exec(ExpandConstant('{cmd}'), '/c taskkill /f /im card-helper.exe',
         '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    Sleep(1000);
    Result := True;
end;

// 卸载初始化:先关闭正在运行的读卡助手
function InitializeUninstall(): Boolean;
var
    ResultCode: Integer;
begin
    Exec(ExpandConstant('{cmd}'), '/c taskkill /f /im card-helper.exe',
         '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    Sleep(1500);
    Result := True;
end;