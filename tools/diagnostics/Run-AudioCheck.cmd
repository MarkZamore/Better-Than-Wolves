@echo off
setlocal
set "BTW_AUDIO_ROOT=%~dp0"
if not "%~1"=="" set "BTW_AUDIO_ROOT=%~f1\"
if not exist "%BTW_AUDIO_ROOT%LANMinecraft.exe" (
  echo Put this file and Test-LegacyAudioLoad.ps1 beside LANMinecraft.exe.
  exit /b 2
)
set "BTW_AUDIO_PS="
for /r "%BTW_AUDIO_ROOT%Minecraft\Launcher\PowerShell" %%P in (pwsh.exe) do if exist "%%P" set "BTW_AUDIO_PS=%%~fP"
if not defined BTW_AUDIO_PS for /f "delims=" %%P in ('where pwsh.exe 2^>nul') do if not defined BTW_AUDIO_PS set "BTW_AUDIO_PS=%%P"
if not defined BTW_AUDIO_PS (
  echo PowerShell 7 is required. No game files have been changed.
  exit /b 2
)
set "BTW_AUDIO_OUT=%BTW_AUDIO_ROOT%artifacts\agent-runs\audio-check-%RANDOM%-%RANDOM%"
if not "%~2"=="" set "BTW_AUDIO_OUT=%~f2"
"%BTW_AUDIO_PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0Test-LegacyAudioLoad.ps1" -AppRoot "%BTW_AUDIO_ROOT%." -OutputDirectory "%BTW_AUDIO_OUT%" -UseJavaw
set "BTW_AUDIO_RESULT=%ERRORLEVEL%"
echo Result: "%BTW_AUDIO_OUT%\audio-load.json"
if "%~1"=="" pause
exit /b %BTW_AUDIO_RESULT%
