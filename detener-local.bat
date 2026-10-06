@echo off
REM =====================================================================
REM Detiene la plataforma completa en local (Windows).
REM
REM   detener-local.bat
REM
REM Los contenedores se detienen y eliminan, pero los volumenes de
REM datos (postgres-data) se conservan para poder reanudir sin perder
REM la base de datos.
REM =====================================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo.
echo ==^> Deteniendo servicios
docker compose down
if errorlevel 1 (
    echo     Error al detener los servicios.
    exit /b 1
)

echo     Plataforma detenida
echo.
echo   Para volver a levantar:  arranque-local.bat
endlocal
