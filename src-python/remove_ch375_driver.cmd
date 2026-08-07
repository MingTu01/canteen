@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
title 企业智慧食堂终端 - CH375 读卡器驱动清理
echo ============================================
echo  企业智慧食堂终端 - CH375 读卡器驱动清理
echo ============================================
echo.
echo [1/1] 正在查找 CH375 读卡器驱动...
set "TMPFILE=%TEMP%\ch375_enum_%RANDOM%.txt"
set "FOUND="

rem 把驱动列表转储到临时文件(line-by-line 便于跨行关联)
pnputil /enum-drivers > "%TMPFILE%" 2>nul
if not exist "%TMPFILE%" goto :done

set "curPub="
for /f "tokens=1,* delims=:" %%a in ("%TMPFILE%") do (
  rem 记录当前驱动块的 Published Name(形如 oem0.inf),它出现在块首
  rem %%b 可能带前导空格,去掉所有空格(Published Name 恒为 oemN.inf,不含空格)
  if /i "%%a" equ "Published Name" (
    set "curPub=%%b"
    set "curPub=!curPub: =!"
  )
  rem 当前块是否与 CH375 相关(Original Name / Driver Description 等含 CH375)
  echo %%a %%b | findstr /i "CH375" >nul 2>&1
  if not errorlevel 1 (
    if defined curPub (
      echo  找到读卡器驱动: !curPub!
      pnputil /delete-driver !curPub! /uninstall /force
      set "FOUND=1"
    )
  )
)
del /q "%TMPFILE%" 2>nul

:done
if not defined FOUND echo  未找到 CH375 读卡器驱动,无需删除。
echo.
echo [完成] CH375 驱动清理结束。
ping -n 3 127.0.0.1 >nul
exit /b 0