@echo off
setlocal
echo ==========================================================
echo   PC 2 — Servidor Coordinador (IP LAN: 192.168.1.13)     
echo ==========================================================

set JAR_PATH=Backend-Microservicios\coordinator-service\target\coordinator-service-0.0.1-SNAPSHOT.jar

if not exist "%JAR_PATH%" (
    echo [ADVERTENCIA] No se encontro el JAR. Compilando coordinator-service con Maven...
    cd Backend-Microservicios
    call mvn clean package -pl coordinator-service -am -DskipTests
    cd ..
)

echo [INICIANDO] Coordinador en puerto 7000 apuntando a Replicas en 192.168.1.10...
echo ==========================================================

java -jar "%JAR_PATH%" --spring.profiles.active=lan %*
endlocal
