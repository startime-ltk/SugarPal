@echo off
rem ============================================================
rem  糖伴SugarPal - 分发包启动脚本
rem  目录结构（由 assembly.xml 装配）：
rem    SugarPal.bat          本脚本
rem    SugarPal.jar          应用本体（含 gson/h2/poi/openpdf）
rem    javafx\*.jar          JavaFX 运行时
rem ============================================================
setlocal enabledelayedexpansion
set "DIR=%~dp0"

rem 收集 javafx 目录下所有 jar 作为 module-path
set "JFX_LIST="
for %%f in ("%DIR%javafx\*.jar") do (
    if "!JFX_LIST!"=="" (set "JFX_LIST=%%f") else (set "JFX_LIST=!JFX_LIST!;%%f")
)

if not exist "%DIR%SugarPal.jar" (
    echo [糖伴] 找不到 SugarPal.jar，程序文件不完整
    pause
    exit /b 1
)

rem 注意：这里用 -cp + 主类名启动，不要用 -jar。
rem -jar 会让 java 忽略 --module-path，JavaFX 就找不到了。
java -p "%JFX_LIST%" --add-modules javafx.controls,javafx.fxml ^
     -cp "%DIR%SugarPal.jar" com.bloodsugar.BloodSugarApp
pause
endlocal
