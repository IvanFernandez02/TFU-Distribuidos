@echo off
setlocal
echo ==========================================================
echo   PC 3 — Balanceador de Carga (IP LAN: 192.168.1.12)     
echo ==========================================================

set JAR_PATH=Backend-Microservicios\loadbalancer-service\target\loadbalancer-service-0.0.1-SNAPSHOT.jar

if not exist "%JAR_PATH%" (
    echo [ADVERTENCIA] No se encontro el JAR. Compilando loadbalancer-service con Maven...
    cd Backend-Microservicios
    call mvn clean package -pl loadbalancer-service -am -DskipTests
    cd ..
)

echo [INICIANDO] Balanceador en puerto 8000 apuntando al Coordinador en 192.168.1.13:7000...
echo ==========================================================

java -jar "%JAR_PATH%" --spring.profiles.active=lan %*
endlocal
