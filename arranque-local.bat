@echo off
REM =====================================================================
REM Levanta la plataforma completa en local (Windows).
REM
REM   arranque-local.bat
REM
REM Pasos: configuracion -> modelos -> construccion -> arriba -> verificacion
REM =====================================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "MODELOS=qwen3:8b nomic-embed-text"

echo.
echo ==^> Deteniendo instancia previa (si existe)
call detener-local.bat

echo.
echo ==^> Preparando configuracion
docker version >nul 2>&1
if errorlevel 1 (
    echo     Docker no esta instalado o no esta corriendo.
    echo     Inicia Docker Desktop y vuelve a ejecutar este script.
    exit /b 1
)
docker compose version >nul 2>&1
if errorlevel 1 (
    echo     Docker Compose v2 no esta disponible.
    exit /b 1
)

if not exist ".env" (
    copy ".env.example" ".env" >nul
    echo     Se creo .env a partir de .env.example ^(revisa las contrasenas^)
) else (
    echo     .env ya existe, se respeta
)

REM Cargar variables de .env para usar en este script (cmd no las lee automaticamente)
for /f "usebackq tokens=*" %%A in (`type .env ^| findstr /R "^[^#]"`) do (
    set "linea=%%A"
    if not "!linea!"=="" set !linea!
)

echo.
echo ==^> Verificando modelos de Ollama
REM Si Ollama corre como servicio del compose se usa el puerto del host.
for %%M in (%MODELOS%) do (
    curl.exe -sf "http://localhost:11434/api/tags" -o tags.tmp
    findstr "%%M" tags.tmp >nul
    if errorlevel 1 (
        echo     Descargando %%M ^(puede tardar varios minutos^)
        ollama pull %%M >nul 2>&1
    ) else (
        echo     %%M ya esta descargado
    )
    del tags.tmp >nul 2>&1
)

echo.
echo ==^> Construyendo imagenes
REM Se suprime la salida de build: los codigos ANSI de Docker corrompen
REM el estado de la consola y rompen los redirects de curl posteriormente.
docker compose build >build.log 2>&1
if errorlevel 1 (
    type build.log
    echo.
    echo     Error construyendo las imagenes.
    exit /b 1
)
del build.log >nul 2>&1
echo     Imagenes construidas

echo.
echo ==^> Levantando servicios
REM No se activa el perfil with-ollama a proposito: se usa el Ollama del host,
REM que es donde estan los modelos. El servicio de Ollama del compose
REM ocuparia el mismo 11434 y no veria los modelos del host.
docker compose up -d
if errorlevel 1 exit /b 1

echo.
echo ==^> Esperando a que el backend este listo
REM El health check se ejecuta en PowerShell (health-check.ps1) porque los
REM codigos ANSI que Docker escribe en la consola corrompen los redirects
REM de cmd, produciendo errores de redireccion.
powershell -NoProfile -ExecutionPolicy Bypass -File health-check.ps1 %BACKEND_PORT%
if errorlevel 1 (
    echo     El backend no respondio tras 5 minutos.
    echo     Revisa los logs con: docker compose logs backend
    exit /b 1
)

:listo
echo.
echo ==^> Estado
curl.exe -s "http://localhost:%BACKEND_PORT%/actuator/health"
echo.
echo.
echo   Frontend:  http://localhost:%FRONTEND_PORT%
echo   API:       http://localhost:%BACKEND_PORT%
echo   OpenAPI:   http://localhost:%BACKEND_PORT%/swagger-ui.html
echo   Health:    http://localhost:%BACKEND_PORT%/actuator/health
echo.
endlocal
