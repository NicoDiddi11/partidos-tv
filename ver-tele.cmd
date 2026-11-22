@echo off
rem Espejo de la tele (onn 4K) en la PC, con scrcpy. El sonido sigue saliendo por la tele.
rem Lo abre el acceso directo "Ver tele" del escritorio.

set "TELE=192.168.0.181:5555"
set "ADB=%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"

rem Busca scrcpy en la carpeta de winget (sirve aunque se actualice de version).
set "SCRCPY="
for /d %%D in ("%LOCALAPPDATA%\Microsoft\WinGet\Packages\Genymobile.scrcpy_*") do (
  for /d %%V in ("%%D\scrcpy-win64-*") do set "SCRCPY=%%V\scrcpy.exe"
)
if not defined SCRCPY (
  powershell -NoProfile -Command "Add-Type -AssemblyName PresentationFramework; [System.Windows.MessageBox]::Show('No encuentro scrcpy. Reinstalalo con: winget install Genymobile.scrcpy','Ver tele')" >nul
  exit /b 1
)

rem Despues de reiniciar la PC, adb no esta conectado a la tele: se reconecta.
"%ADB%" connect %TELE% >nul 2>&1

"%SCRCPY%" -s %TELE% --no-audio --max-size 1280 --max-fps 30 --video-bit-rate 6M --window-title "Tele (onn 4K)"
if errorlevel 1 (
  powershell -NoProfile -Command "Add-Type -AssemblyName PresentationFramework; [System.Windows.MessageBox]::Show('No se pudo conectar con la tele. Fijate que este prendida y en la misma red Wi-Fi que la compu.','Ver tele')" >nul
)
