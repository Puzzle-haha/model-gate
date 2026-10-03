# 从 .env 读数据库凭据并启动应用。
# 密码只存在于 .env（已 gitignore）和进程环境变量里，不进代码、不进 application.yml。
#
# 用法：
#   .\run.ps1              使用 application.yml 的默认配置
#                          （可用模型：mock-ok / mock-slow / mock-hang ... 够做故障注入实验）
#   .\run.ps1 -LocalTest   额外加载 tools/localtest.yml
#                          （接本地假上游，可用模型：fake-* 和 eval-*）
#
# ⚠️ 本文件必须保存为「UTF-8 with BOM」。
#    Windows PowerShell 5.1 对【没有 BOM】的 .ps1 会按系统 ANSI（中文机器上是 GBK）解码，
#    中文注释会变成乱码，而且错位的字节可能被当成引号或大括号 —— 直接语法报错。
#    这个坑真实发生过：脚本内容完全正确，只是缺了 BOM，一跑就 ParseException。
param(
    [switch]$LocalTest
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$envFile = Join-Path $root '.env'

if (-not (Test-Path $envFile)) {
    throw "缺少 $envFile。请先复制 .env.example 为 .env 并填写。"
}

Get-Content $envFile |
    Where-Object { $_ -match '^\s*[^#\s][^=]*=' } |
    ForEach-Object {
        $kv = $_ -split '=', 2
        Set-Item -Path "env:$($kv[0].Trim())" -Value $kv[1].Trim()
    }

Write-Host "已加载环境变量: DB_USER=$env:DB_USER  SERVER_PORT=$env:SERVER_PORT"

# 数值型变量做一次显式校验。
# 起因：编辑 .env 时不小心把两行拼成一行（SERVER_PORT=8081DEEPSEEK_API_KEY=），
# Spring 报的是 "Invalid value '8081DEEPSEEK_API_KEY=' for configuration property
# 'server.port'" —— 信息量很大但指向很偏，第一眼很难联想到是 .env 的换行问题。
# 在入口处拦一下，报错就能直接指到根因。
if ($env:SERVER_PORT -and $env:SERVER_PORT -notmatch '^\d+$') {
    throw "SERVER_PORT 不是合法数字: '$env:SERVER_PORT'。请检查 .env 是否有多余或缺失的换行符。"
}

$mvnArgs = @('-f', (Join-Path $root 'pom.xml'), 'spring-boot:run')

if ($LocalTest) {
    $cfg = (Join-Path $root 'tools\localtest.yml')
    if (-not (Test-Path $cfg)) {
        throw "找不到 $cfg。"
    }
    # 用正斜杠：Spring 的 file: 前缀在 Windows 上更认这种写法
    $cfgUrl = 'file:' + ($cfg -replace '\\', '/')
    Write-Host "本地联调模式：额外加载 $cfgUrl"
    Write-Host "  可用模型: fake-fast / fake-ok / fake-500 / eval-strong / eval-weak / eval-cheap"
    Write-Host "  记得另开一个终端跑:  node tools\fake-upstream.mjs"
    $mvnArgs += "-Dspring-boot.run.arguments=--spring.config.additional-location=$cfgUrl"
}

& (Join-Path $root 'mvnw.cmd') @mvnArgs
