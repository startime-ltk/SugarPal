# ============================================================
# 糖伴SugarPal PC端 jpackage 打包命令（修复 java.sql 缺失）
# 根因：jlink 裁剪运行时缺 java.sql，导致 NoClassDefFoundError:
#       java/sql/SQLException，应用启动即崩。
# 修复：--add-modules 必须包含 java.sql,java.naming（H2 所需）
#       与 java.net.http（AI 建议模块 HTTP 调用所需）。
# 使用：mvn clean package 后执行本脚本。
# 注意：input 目录必须干净，只含 SugarPal.jar + javafx/。
# ============================================================

$ErrorActionPreference = "Stop"

$srcRoot  = "F:\项目成品安装包以及源码\糖伴SugarPal\源码\糖伴SugarPal-源码"
$destRoot = "F:\项目成品安装包以及源码\糖伴SugarPal\成品安装包"
$inputDir = Join-Path $env:TEMP "sugarpal-input"
$icon     = Join-Path $srcRoot "src\main\resources\icon.ico"

# 1. 构建
Push-Location $srcRoot
mvn clean package
if ($LASTEXITCODE -ne 0) { throw "mvn build failed" }
Pop-Location

# 2. 干净 input 目录（仅 jar + javafx）
if (Test-Path $inputDir) { Remove-Item $inputDir -Recurse -Force }
New-Item -ItemType Directory -Path $inputDir -Force | Out-Null
Copy-Item (Join-Path $srcRoot "target\SugarPal.jar") $inputDir
Copy-Item (Join-Path $srcRoot "target\javafx") $inputDir -Recurse

# 3. jpackage：模块清单必须含 java.sql,java.naming,java.net.http
jpackage --type app-image `
  --input $inputDir `
  --main-jar SugarPal.jar `
  --module-path (Join-Path $inputDir "javafx") `
  --add-modules javafx.controls,javafx.fxml,java.sql,java.naming,java.net.http `
  --name 糖伴SugarPal `
  --icon $icon `
  --dest $destRoot `
  --app-version "1.3.1"
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

# 4. Inno Setup 编译安装包
& "F:\Inno Setup 7\ISCC.exe" "F:\项目成品安装包以及源码\糖伴SugarPal\打包脚本\糖伴SugarPal-installer.iss"
if ($LASTEXITCODE -ne 0) { throw "ISCC failed" }

Write-Host "打包完成：$destRoot\糖伴SugarPal-1.3.1-安装包.exe"
