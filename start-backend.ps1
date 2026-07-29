# 启动 dbdoc-ai 后端。
# 脚本位于项目根目录，默认以脚本所在目录作为项目根定位 backend；
# 亦可用 -ProjectRoot 参数显式指定（支持在任意工作目录下启动）。
param(
    [string]$ProjectRoot = $PSScriptRoot
)

$backendDir = Join-Path $ProjectRoot "backend"
if (-not (Test-Path $backendDir)) {
    Write-Error "未找到后端目录: $backendDir"
    exit 1
}

Set-Location $backendDir

$jar = Join-Path $backendDir "target/dbdoc-ai-backend-0.1.0.jar"
if (-not (Test-Path $jar)) {
    Write-Error "未找到构建产物: $jar （请先执行 mvn -q -DskipTests package 进行构建）"
    exit 1
}

java -jar $jar
