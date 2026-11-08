@echo off
setlocal
set "BTW_AUDIO_RESULT=2"
set "BTW_AUDIO_ROOT=%~dp0"
if not "%~1"=="" set "BTW_AUDIO_ROOT=%~f1\"
set "BTW_AUDIO_LOG=%~dp0AudioCheck-start.log"
> "%BTW_AUDIO_LOG%" echo AudioCheck v2 startup
if not exist "%~dp0Test-LegacyAudioLoad.ps1" (
  >> "%BTW_AUDIO_LOG%" echo ERROR: Extract BOTH files from AudioCheck-v2.zip before running.
  goto finished
)
if not exist "%BTW_AUDIO_ROOT%LANMinecraft.exe" (
  >> "%BTW_AUDIO_LOG%" echo ERROR: Put BOTH checker files beside LANMinecraft.exe, not in the mods folder.
  goto finished
)
set "BTW_AUDIO_PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if exist "%SystemRoot%\Sysnative\WindowsPowerShell\v1.0\powershell.exe" set "BTW_AUDIO_PS=%SystemRoot%\Sysnative\WindowsPowerShell\v1.0\powershell.exe"
if not exist "%BTW_AUDIO_PS%" (
  >> "%BTW_AUDIO_LOG%" echo ERROR: Windows PowerShell could not be found. No game files have been changed.
  goto finished
)
set "BTW_AUDIO_OUT=%BTW_AUDIO_ROOT%artifacts\agent-runs\audio-check-%RANDOM%-%RANDOM%"
if not "%~2"=="" set "BTW_AUDIO_OUT=%~f2"
>> "%BTW_AUDIO_LOG%" echo Starting installed Java and OpenAL check. This can take up to one minute.
"%BTW_AUDIO_PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0Test-LegacyAudioLoad.ps1" -AppRoot "%BTW_AUDIO_ROOT%." -OutputDirectory "%BTW_AUDIO_OUT%" -UseJavaw >> "%BTW_AUDIO_LOG%" 2>&1
set "BTW_AUDIO_RESULT=%ERRORLEVEL%"
if exist "%BTW_AUDIO_OUT%\probe-private.log" (
  >> "%BTW_AUDIO_LOG%" echo.
  >> "%BTW_AUDIO_LOG%" echo --- OpenAL loader details ---
  type "%BTW_AUDIO_OUT%\probe-private.log" >> "%BTW_AUDIO_LOG%"
)
>> "%BTW_AUDIO_LOG%" echo Exit code: %BTW_AUDIO_RESULT%
:finished
echo.
if exist "%BTW_AUDIO_LOG%" type "%BTW_AUDIO_LOG%"
echo.
echo Send this file: "%BTW_AUDIO_LOG%"
if defined BTW_AUDIO_OUT echo Detailed result, if created: "%BTW_AUDIO_OUT%\audio-load.json"
if "%~1"=="" pause
exit /b %BTW_AUDIO_RESULT%
