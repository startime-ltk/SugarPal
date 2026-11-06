@echo off
rem ============================================================
rem  糖伴SugarPal - Windows 本地启动脚本（开发用）
rem  用法：先在本目录执行 mvn clean package，再双击本文件
rem  注意：cd /d "%~dp0" 保证无论从哪个目录双击都能找到工程
rem ============================================================
setlocal
cd /d "%~dp0"

set "JAR=target\SugarPal.jar"
set "JFX=target\javafx"
set "MAIN=com.bloodsugar.BloodSugarApp"

if not exist "%JAR%" (
    echo [糖伴] 找不到 %JAR%
    echo [糖伴] 请先在本目录执行：mvn clean package
    pause
    exit /b 1
)

rem 优先用打包时附带的运行时，没有则回退到系统 PATH 里的 javaw
if exist "%~dp0runtime\bin\javaw.exe" (
    set "JAVAW=%~dp0runtime\bin\javaw.exe"
) else (
    set "JAVAW=javaw"
)

echo [糖伴] 启动中...
start "" "%JAVAW%" -cp "%JAR%" --module-path "%JFX%" --add-modules javafx.controls,javafx.fxml %MAIN%
endlocal
