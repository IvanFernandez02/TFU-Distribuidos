@echo off
setlocal

echo ==========================================================
echo   Construyendo Ecosistema RSL (Microservicios + React)   
echo ==========================================================

echo.
echo 1) Compilando Backend Microservicios (Maven)...
cd Backend-Microservicios
call mvn clean package -DskipTests
cd ..

echo.
echo 2) Instalando dependencias del Frontend (npm)...
cd Frontend
call npm install
cd ..

echo.
echo [EXITO] Todo construido con exito y listo para el laboratorio LAN!
echo ==========================================================
endlocal
