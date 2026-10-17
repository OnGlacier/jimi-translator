@echo off
rem Windows 构建：需要 JDK 17+，在 PATH 里
setlocal
set ROOT=%~dp0
if exist "%ROOT%build" rmdir /s /q "%ROOT%build"
mkdir "%ROOT%build\classes"
dir /s /b "%ROOT%src\*.java" > "%ROOT%build\sources.txt"
javac -encoding UTF-8 -source 17 -target 17 -d "%ROOT%build\classes" @"%ROOT%build\sources.txt" || exit /b 1
jar --create --file "%ROOT%build\jimi-translator.jar" --main-class com.jimi.desktop.Main -C "%ROOT%build\classes" .
echo BUILD_OK -^> %ROOT%build\jimi-translator.jar
