# 从 .env 读数据库凭据并启动应用。
# 密码只存在于 .env（已 gitignore）和进程环境变量里，不进代码、不进 application.yml。
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

& (Join-Path $root 'mvnw.cmd') -f (Join-Path $root 'pom.xml') spring-boot:run
