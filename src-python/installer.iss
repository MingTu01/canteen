; =============================================================================
; 企业智慧食堂终端 - Inno Setup 安装包脚本
; =============================================================================
; 用途:将 PyInstaller 产物(dist/canteen-terminal/)打包为正式的 EXE 安装包
;
; 安装包功能:
;   1. 安装终端程序到 C:\Program Files\CanteenTerminal\
;   2. 自动安装 CH375 读卡器驱动(调用 pnputil)
;   3. 创建开始菜单快捷方式 + 桌面快捷方式
;   4. 设置开机自启(可选)
;   5. 提供完整的卸载程序(卸载时还原驱动)
;
; 打包前置条件:
;   1. 已安装 Inno Setup 6+(https://jrsoftware.org/isdl.php)
;   2. 已运行 PyInstaller 生成 dist/canteen-terminal/ 目录
;   3. 已将 CH375 驱动文件放入 src-python/drivers/ 目录
;
; 打包命令(Inno Setup 安装目录下有 ISCC.exe):
;   "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" installer.iss
;
; 产物:
;   output/CanteenTerminal-Setup-<版本号>.exe  (正式安装包)
; =============================================================================
#define MyAppName "企业智慧食堂终端"
#define MyAppNameEn "CanteenTerminal"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "Enterprise Canteen System"
#define MyAppExeName "canteen-terminal.exe"

[Setup]
; 应用基本信息
AppId={{B7C3E8A1-2026-4F8D-9085-21XYZCYANTEN}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL=https://canteen.908521.xyz
AppSupportURL=https://canteen.908521.xyz
AppUpdatesURL=https://canteen.908521.xyz

; 安装目录(32 位系统装到 Program Files,64 位装到 Program Files)
; {autopf} 会自动根据系统位数选择 Program Files 或 Program Files (x86)
DefaultDirName={autopf}\{#MyAppNameEn}
DefaultGroupName={#MyAppName}

; 输出配置
OutputDir=output
OutputBaseFilename=CanteenTerminal-Setup-{#MyAppVersion}
Compression=lzma2/ultra64
SolidCompression=yes

; 兼容 Win7 SP1 / Win8 / Win10 / Win11 的 32 位和 64 位系统
; 不限制 ArchitecturesAllowed,允许在 x86 和 x64 系统上安装
; 64 位系统上以 64 位模式安装(可获得更好的性能),32 位系统自动降级为 32 位模式
ArchitecturesInstallIn64BitMode=x64compatible
ArchitecturesAllowed=x86compatible x64compatible

; 安装包外观
SetupIconFile=icon.ico
WizardSmallImageFile=wizard-small.bmp
WizardImageFile=wizard-large.bmp
UninstallDisplayIcon={app}\icon.ico
UninstallDisplayName={#MyAppName}

; 权限要求(需要管理员权限安装驱动)
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=dialog

; 安装向导语言
ShowLanguageDialog=no
LanguageDetectionMethod=none

; 禁用"安装完成后运行"(读卡器程序需要单实例,避免冲突)
DisableReadyPage=no
DisableProgramGroupPage=no

[Languages]
Name: "chinesesimp"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "在桌面创建快捷方式"; GroupDescription: "附加选项:"; Flags: checkedonce
Name: "startup"; Description: "开机自动启动"; GroupDescription: "附加选项:"; Flags: unchecked

[Files]
; 主程序目录(PyInstaller onedir 产物,递归打包)
Source: "dist\canteen-terminal\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

; 图标文件(打包到安装目录,供快捷方式和卸载程序使用)
Source: "icon.ico"; DestDir: "{app}"; Flags: ignoreversion

; CH375 驱动文件(只打包 INF/SYS/CAT/DLL,排除易被安全软件误报的第三方 EXE)
; 驱动安装由系统自带 pnputil 完成,不依赖第三方安装程序
Source: "drivers\*"; DestDir: "{app}\drivers"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "SETUP.EXE,DRVSETUP64.exe"; Check: DriverFilesExist

[Icons]
; 开始菜单快捷方式(使用食堂主题图标)
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\icon.ico"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"; IconFilename: "{app}\icon.ico"

; 桌面快捷方式(可选,使用食堂主题图标)
Name: "{commondesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\icon.ico"; Tasks: desktopicon

; 开机自启(可选,注册到 HKLM Run)
Name: "{commonstartup}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\icon.ico"; Tasks: startup

[Run]
; 安装 CH375 读卡器驱动 - 只使用系统自带 pnputil(不会触发安全软件拦截)
; 兼容 Win7/8/8.1(旧语法 -a)和 Win10/11(新语法 /add-driver /install)
; 先尝试新语法,失败则回退到旧语法,确保各版本 Windows 均可安装
Filename: "{cmd}"; Parameters: "/c pnputil /add-driver ""{app}\drivers\CH375WDM.INF"" /install 2>nul || pnputil -a ""{app}\drivers\CH375WDM.INF"""; \
    StatusMsg: "正在安装 CH375 读卡器驱动..."; \
    Flags: runhidden waituntilterminated; \
    Check: DriverFilesExist

[UninstallRun]
; 卸载时移除 CH375 驱动(可选,通常保留驱动避免影响其他设备)
; 如需卸载驱动,取消下面注释:
; Filename: "{cmd}"; Parameters: "/c pnputil /delete-driver oem*.inf /uninstall /force"; Flags: runhidden

[UninstallDelete]
; 清理安装目录残留(旧版可能在安装目录下留有 data/config.json)
Type: filesandordirs; Name: "{app}\data"
Type: filesandordirs; Name: "{app}\config.json"
Type: filesandordirs; Name: "{app}\qt.conf"

[Code]
// =============================================================================
// 自定义函数:检测 drivers 目录是否有驱动 INF 文件
// =============================================================================
function DriverFilesExist(): Boolean;
var
    InfPath: String;
begin
    InfPath := ExpandConstant('{app}\drivers\CH375WDM.INF');
    Result := FileExists(InfPath);
end;

// 安装初始化:关闭正在运行的终端进程(覆盖安装时文件被占用会失败)
function InitializeSetup(): Boolean;
var
    ResultCode: Integer;
begin
    Exec(ExpandConstant('{cmd}'), '/c taskkill /f /im canteen-terminal.exe',
         '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    Sleep(1000);
    Result := True;
end;

// =============================================================================
// 卸载初始化:先关闭正在运行的终端进程
// =============================================================================
// 关键:如果终端还在运行,_internal 目录里的 PyQt5 DLL / QtWebEngineProcess.exe
// 会被进程占用,Inno Setup 无法删除这些文件,导致整个安装目录残留。
// 必须在卸载文件之前(taskkill)关闭进程,并等待文件句柄释放。
function InitializeUninstall(): Boolean;
var
    ResultCode: Integer;
begin
    // 强制结束终端进程(不存在时 taskkill 返回非零,忽略即可)
    Exec(ExpandConstant('{cmd}'), '/c taskkill /f /im canteen-terminal.exe',
         '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    // 等待 Windows 释放文件句柄(DLL 卸载有延迟)
    Sleep(1500);
    Result := True;
end;

// =============================================================================
// 卸载时清理所有用户数据(配置、缓存、QtWebEngine 持久化数据)
// =============================================================================
// 数据存放位置(见 config.py / main.py):
//   %APPDATA%\CanteenTerminal\config.json          — 配置文件(server_url 等)
//   %LOCALAPPDATA%\CanteenTerminal\                — QtWebEngine 持久化数据 +
//     terminal.log 日志文件
//     (data/LocalStorage/IndexedDB/Cookies/Network State/GPUCache 等)
//
// 注意:{userappdata}/{userlocalappdata} 指执行卸载的用户的目录。
// 若程序被多个 Windows 用户使用,其他用户的数据需各自登录后清理(标准 Windows 行为)。
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
    AppDataDir: String;
    LocalAppDataDir: String;
begin
    if CurUninstallStep = usPostUninstall then
    begin
        AppDataDir := ExpandConstant('{userappdata}\CanteenTerminal');
        LocalAppDataDir := ExpandConstant('{userlocalappdata}\CanteenTerminal');

        // 清理 %APPDATA%\CanteenTerminal(配置文件)
        if DirExists(AppDataDir) then
        begin
            DelTree(AppDataDir, True, True, True);
        end;

        // 清理 %LOCALAPPDATA%\CanteenTerminal(QtWebEngine 数据/缓存/日志)
        if DirExists(LocalAppDataDir) then
        begin
            DelTree(LocalAppDataDir, True, True, True);
        end;
    end;
end;
