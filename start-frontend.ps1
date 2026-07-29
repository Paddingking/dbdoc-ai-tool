# 启动 dbdoc-ai 前端（Vite dev）。
# 脚本位于项目根目录，默认以脚本所在目录作为项目根定位 frontend；
# 亦可用 -ProjectRoot 参数显式指定（支持在任意工作目录下启动）。
param(
    [string]$ProjectRoot = $PSScriptRoot
)

$frontendDir = Join-Path $ProjectRoot "frontend"
if (-not (Test-Path $frontendDir)) {
    Write-Error "未找到前端目录: $frontendDir"
    exit 1
}

Set-Location $frontendDir
npm run dev
