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

echo.
echo ==^> Verificando modelos de Ollama
REM Si Ollama corre como servicio del compose se usa el puerto del host.
for %%M in (%MODELOS%) do (
    curl -sf "http://localhost:11434/api/tags" | findstr /C:"\"%%M\"" >nul
    if errorlevel 1 (
        echo     Descargando %%M ^(puede tardar varios minutos^)
        ollama pull %%M
    ) else (
        echo     %%M ya esta descargado
    )
)

echo.
echo ==^> Construyendo imagenes
docker compose build
if errorlevel 1 exit /b 1

echo.
echo ==^> Levantando servicios
docker compose --profile with-ollama up -d
if errorlevel 1 exit /b 1

echo.
echo ==^> Esperando a que el backend este listo
for /l %%I in (1,1,60) do (
    curl -sf "http://localhost:8081/actuator/health" >nul 2>&1
    if not errorlevel 1 (
        echo     Backend respondiendo
        goto :listo
    )
    timeout /t 5 /nobreak >nul
)
echo     El backend no respondio tras 5 minutos.
echo     Revisa los logs con: docker compose logs backend
exit /b 1

:listo
echo.
echo ==^> Estado
curl -s "http://localhost:8081/actuator/health"
echo.
echo.
echo   Frontend:  http://localhost:5173
echo   API:       http://localhost:8081
echo   OpenAPI:   http://localhost:8081/swagger-ui.html
echo   Health:    http://localhost:8081/actuator/health
echo.
endlocal
