@echo off
setlocal
chcp 65001 >nul

echo 正在生成新的 RUNNER_ENCRYPTION_KEY...
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $key=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)); [Environment]::SetEnvironmentVariable('RUNNER_ENCRYPTION_KEY',$key,'User'); Write-Host ('已保存到当前 Windows 用户环境变量，长度：' + $key.Length + ' 个字符。'); Write-Host '请重新打开 PowerShell 或命令提示符后再启动服务。'"
if errorlevel 1 (
  echo 生成或保存密钥失败。
  pause
  exit /b 1
)

echo.
echo 注意：不要把密钥原文提交到 Git 或发送给他人。
pause
