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
; 版本号默认值;build_installer.py 会从 VERSIONS.json 读取并通过 /DMyAppVersion 覆盖
#ifndef MyAppVersion
  #define MyAppVersion "1.0.9"
#endif
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
SetupIconFile=terminal_icon.ico
WizardSmallImageFile=wizard-small.bmp
WizardImageFile=wizard-large.bmp
UninstallDisplayIcon={app}\terminal_icon.ico
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
Source: "terminal_icon.ico"; DestDir: "{app}"; Flags: ignoreversion

; CH375 驱动清理脚本(卸载时勾选"移除驱动"才执行)
; 保留在安装目录直至卸载结束,不能加 deleteafterinstall,否则卸载时脚本已不存在
Source: "remove_ch375_driver.cmd"; DestDir: "{app}"; Flags: ignoreversion

; CH375 驱动文件(只打包 INF/SYS/CAT/DLL,排除易被安全软件误报的第三方 EXE)
; 驱动安装由系统自带 pnputil 完成,不依赖第三方安装程序
Source: "drivers\*"; DestDir: "{app}\drivers"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "SETUP.EXE,DRVSETUP64.exe"; Check: DriverFilesExist

[Icons]
; 开始菜单快捷方式(使用食堂主题图标)
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\terminal_icon.ico"
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"; IconFilename: "{app}\terminal_icon.ico"

; 桌面快捷方式(可选,使用食堂主题图标)
Name: "{commondesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\terminal_icon.ico"; Tasks: desktopicon

; 开机自启(可选,注册到 HKLM Run)
Name: "{commonstartup}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\terminal_icon.ico"; Tasks: startup

[Run]
; 安装 CH375 读卡器驱动 - 只使用系统自带 pnputil(不会触发安全软件拦截)
; 兼容 Win7/8/8.1(旧语法 -a)和 Win10/11(新语法 /add-driver /install)
; 先尝试新语法,失败则回退到旧语法,确保各版本 Windows 均可安装
Filename: "{cmd}"; Parameters: "/c pnputil /add-driver ""{app}\drivers\CH375WDM.INF"" /install 2>nul || pnputil -a ""{app}\drivers\CH375WDM.INF"""; \
    StatusMsg: "正在安装 CH375 读卡器驱动..."; \
    Flags: runhidden waituntilterminated; \
    Check: DriverFilesExist

; 注:CH375 驱动的移除不再通过 [UninstallRun] 执行,而是在卸载向导的
; 自定义页面(见 [Code])中由用户勾选后,于 usUninstall 阶段调用 remove_ch375_driver.cmd。
; 这样能保证"默认不勾选 + 充分提示"。

[UninstallDelete]
; 清理安装目录残留(旧版可能在安装目录下留有 data/config.json,或运行时写入的 qt.conf)
Type: filesandordirs; Name: "{app}\data"
Type: filesandordirs; Name: "{app}\config.json"
Type: filesandordirs; Name: "{app}\qt.conf"
Type: filesandordirs; Name: "{app}\_internal\PyQt5\Qt5\bin\qt.conf"
; 清理驱动移除脚本(卸载完成后不再需要)
Type: files; Name: "{app}\remove_ch375_driver.cmd"

[Code]
// =============================================================================
// 全局变量:卸载时是否移除驱动(由 InitializeUninstall 中的 MsgBox 询问)
// =============================================================================
var
    RemoveDriverOnUninstall: Boolean;

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
// 同时备份用户配置(升级安装时旧版卸载程序会清理配置,需先备份安装后再恢复)
var
    ConfigBackupPath: String;

// 备份当前安装用户的 CanteenTerminal 配置文件(config.json)到系统临时目录。
// 只备份当前用户({userappdata})的 config.json(核心配置),不做整目录递归复制,
// 也不遍历 C:\Users\* 全目录树(遍历到 junction/符号链接目录会在 InitializeSetup
// 阶段触发 Inno 的"Runtime Error")。升级安装时旧版卸载程序会清理用户配置,
// 先备份当前用户的配置,安装后再恢复,即可保留服务器地址等核心设置。
procedure BackupUserConfig();
var
    SrcFile, DestFile: String;
begin
    // 用 GetTempDir()(系统临时目录)而非 {tmp} 常量:{tmp} 在 InitializeSetup 阶段
    // 可能尚未就绪,ExpandConstant 会抛运行时错误(Runtime Error at 32:54)。
    ConfigBackupPath := GetTempDir() + '\CanteenTerminalConfigBackup';
    ForceDirectories(ConfigBackupPath);

    SrcFile := ExpandConstant('{userappdata}\CanteenTerminal\config.json');
    if not FileExists(SrcFile) then
        Exit;
    DestFile := ConfigBackupPath + '\config.json';
    try
        FileCopy(SrcFile, DestFile, True);
    except
        // 备份失败不阻断安装:仅打印并继续,避免此处抛运行时错误
        Log('BackupUserConfig: 备份用户配置失败');
    end;
end;

// 恢复之前备份的配置文件(仅当目标 config.json 不存在时恢复,避免覆盖新默认配置)
procedure RestoreUserConfig();
var
    SrcFile, DestFile, UserConfigDir: String;
begin
    if ConfigBackupPath = '' then
        Exit;
    SrcFile := ConfigBackupPath + '\config.json';
    if not FileExists(SrcFile) then
        Exit;
    UserConfigDir := ExpandConstant('{userappdata}\CanteenTerminal');
    DestFile := UserConfigDir + '\config.json';
    if FileExists(DestFile) then
        Exit;
    try
        ForceDirectories(UserConfigDir);
        FileCopy(SrcFile, DestFile, True);
    except
        Log('RestoreUserConfig: 恢复用户配置失败');
    end;
end;

function InitializeSetup(): Boolean;
var
    ResultCode: Integer;
begin
    // 先关闭正在运行的终端进程,避免其占用 config.json 导致备份失败
    Exec(ExpandConstant('{cmd}'), '/c taskkill /f /im canteen-terminal.exe',
         '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    Sleep(1000);
    // 升级安装时旧版卸载程序会清理用户配置,先备份以便安装后恢复
    BackupUserConfig();
    Result := True;
end;

// 安装完成后恢复备份的配置并清理临时备份
procedure CurStepChanged(CurStep: TSetupStep);
begin
    if CurStep = ssPostInstall then
    begin
        RestoreUserConfig();
        if ConfigBackupPath <> '' then
            DelTree(ConfigBackupPath, True, True, True);
    end;
end;

// =============================================================================
// 卸载初始化:先关闭正在运行的终端进程,询问是否移除驱动
// =============================================================================
// 关键:如果终端还在运行,_internal 目录里的 PyQt5 DLL / QtWebEngineProcess.exe
// 会被进程占用,Inno Setup 无法删除这些文件,导致整个安装目录残留。
// 必须在卸载文件之前(taskkill)关闭进程,并等待文件句柄释放。
//
// 注意:Inno Setup 不允许在卸载阶段调用 CreateCustomPage(会报
// "Cannot call CreateCustomPage function during Uninstall" 运行时错误)。
// 改用 MsgBox 询问用户是否移除 CH375 驱动。
function InitializeUninstall(): Boolean;
var
    ResultCode: Integer;
    Msg: String;
begin
    // 强制结束终端进程(不存在时 taskkill 返回非零,忽略即可)
    Exec(ExpandConstant('{cmd}'), '/c taskkill /f /im canteen-terminal.exe',
         '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    // 等待 Windows 释放文件句柄(DLL 卸载有延迟)
    Sleep(1500);

    // 询问是否移除 CH375 驱动(默认不勾选 = 默认保留)
    RemoveDriverOnUninstall := False;
    Msg := '是否同时移除 CH375 读卡器驱动?'#13#10#13#10 +
           '强烈建议保留此驱动,除非您确定不再需要该读卡器设备。'#13#10#13#10 +
           '【风险提示】'#13#10 +
           '  · 移除驱动后,本终端及其他依赖 CH375 驱动的读卡设备将无法刷卡!'#13#10 +
           '  · 若其他软件或设备仍在使用该驱动,其读卡功能会立即失效。'#13#10 +
           '  · 如需恢复,需重新安装本终端或手动重新安装驱动。'#13#10#13#10 +
           '点击"是"移除驱动,点击"否"保留驱动(推荐)。';
    if MsgBox(Msg, mbConfirmation, MB_YESNO) = IDYES then
    begin
        // 二次确认(移除驱动是不可逆操作)
        if MsgBox('确定要移除 CH375 驱动吗?此操作不可撤销!', mbConfirmation, MB_YESNO) = IDYES then
            RemoveDriverOnUninstall := True;
    end;

    Result := True;
end;

// =============================================================================
// 卸载向导:不再需要 NextButtonClick(自定义页面已移除,改用 InitializeUninstall
// 中的 MsgBox 询问驱动移除)
// =============================================================================

// =============================================================================
// 卸载时清理所有用户数据(配置、缓存、QtWebEngine 持久化数据)
// =============================================================================
// 数据存放位置(见 config.py / main.py):
//   %APPDATA%\CanteenTerminal\config.json          — 配置文件(server_url 等)
//   %LOCALAPPDATA%\CanteenTerminal\                — QtWebEngine 持久化数据 +
//     terminal.log 日志文件
//     (data/LocalStorage/IndexedDB/Cookies/Network State/GPUCache 等)
//
// 问题:旧版本卸载只清理"执行卸载的当前用户"的数据。若终端被多个 Windows
// 用户使用过,其他用户目录下的 config.json 与 %LOCALAPPDATA%\CanteenTerminal
// 缓存会一直残留在磁盘上,所谓"卸载清不干净"。
// 解决:遍历 C:\Users\* 下所有用户配置目录,逐一清理,确保彻底。
// =============================================================================

// 清理单个用户目录下的 CanteenTerminal 数据(配置 AppData + 缓存 LocalAppData)
procedure CleanUserCanteenData(const UserProfileDir: String);
var
    AppDataDir: String;
    LocalAppDataDir: String;
begin
    AppDataDir := UserProfileDir + '\AppData\Roaming\CanteenTerminal';
    LocalAppDataDir := UserProfileDir + '\AppData\Local\CanteenTerminal';
    if DirExists(AppDataDir) then
        DelTree(AppDataDir, True, True, True);
    if DirExists(LocalAppDataDir) then
        DelTree(LocalAppDataDir, True, True, True);
end;

// 清理所有用户配置目录下的残留数据(含当前用户)
procedure CleanAllUsersData();
var
    ProfilesDir, UserProfileDir: String;
    FindRec: TFindRec;
begin
    ProfilesDir := ExpandConstant('{sd}\Users');
    if not DirExists(ProfilesDir) then
        Exit;

    if FindFirst(ProfilesDir + '\*', FindRec) then
    begin
        try
            repeat
                if (FindRec.Attributes and FILE_ATTRIBUTE_DIRECTORY) <> 0 then
                begin
                    if (FindRec.Name <> '.') and (FindRec.Name <> '..') then
                    begin
                        UserProfileDir := ProfilesDir + '\' + FindRec.Name;
                        CleanUserCanteenData(UserProfileDir);
                    end;
                end;
            until not FindNext(FindRec);
        finally
            FindClose(FindRec);
        end;
    end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
    AppDataDir: String;
    LocalAppDataDir: String;
    ResultCode: Integer;
begin
    // usUninstall:卸载文件之前触发,此时 {app}\remove_ch375_driver.cmd 仍存在
    if CurUninstallStep = usUninstall then
    begin
        // 用户在 InitializeUninstall 中确认移除驱动
        if RemoveDriverOnUninstall then
        begin
            // 显示脚本窗口,让用户看到驱动清理过程(充足提示)
            Exec(ExpandConstant('{app}\remove_ch375_driver.cmd'), '',
                 '', SW_SHOW, ewWaitUntilTerminated, ResultCode);
        end;
    end
    else if CurUninstallStep = usPostUninstall then
    begin
        // 1. 清理当前用户(显式,兼容非标准用户目录映射)
        AppDataDir := ExpandConstant('{userappdata}\CanteenTerminal');
        LocalAppDataDir := ExpandConstant('{userlocalappdata}\CanteenTerminal');
        if DirExists(AppDataDir) then
            DelTree(AppDataDir, True, True, True);
        if DirExists(LocalAppDataDir) then
            DelTree(LocalAppDataDir, True, True, True);

        // 2. 清理系统上所有用户目录下的残留数据(真正彻底)
        CleanAllUsersData();
    end;
end;
