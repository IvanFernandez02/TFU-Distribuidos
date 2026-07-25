@echo off
setlocal
echo ==========================================================
echo   PC 1 — Servidor de Replicas (IP LAN: 192.168.1.10)     
echo ==========================================================

set JAR_PATH=Backend-Microservicios\replica-service\target\replica-service-0.0.1-SNAPSHOT.jar

if not exist "%JAR_PATH%" (
    echo [ADVERTENCIA] No se encontro el JAR. Compilando replica-service con Maven...
    cd Backend-Microservicios
    call mvn clean package -pl replica-service -am -DskipTests
    cd ..
)

set ID=%1
if "%ID%"=="" goto help
if "%ID%"=="all" goto help

shift

set /a PORT=6000 + %ID%
set DB_URL=jdbc:postgresql://localhost:5432/replica_db_%ID%

echo [INICIANDO] Replica %ID% en puerto %PORT%...
echo [BD] Base de datos: %DB_URL%
echo [INFO] Para probar tolerancia a fallos, cierra esta terminal con Ctrl+C.
echo ==========================================================

java -jar "%JAR_PATH%" --server.port=%PORT% --replica.node-id=%ID% --spring.datasource.url=%DB_URL% %1 %2 %3 %4 %5 %6 %7 %8 %9
goto end

:help
echo Modo de uso: run-pc1-replicas.bat [1^|2^|3]
echo Por favor abre 3 terminales y ejecuta en cada una:
echo   Terminal 1: run-pc1-replicas.bat 1
echo   Terminal 2: run-pc1-replicas.bat 2
echo   Terminal 3: run-pc1-replicas.bat 3
goto end

:end
endlocal
