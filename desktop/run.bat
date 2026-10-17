@echo off
setlocal
set ROOT=%~dp0
if not exist "%ROOT%build\jimi-translator.jar" (
  echo 还没构建：先运行 build.bat
  exit /b 1
)
java -jar "%ROOT%build\jimi-translator.jar" %*
