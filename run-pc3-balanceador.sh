#!/usr/bin/env bash
set -e
echo "=========================================================="
echo "  PC 3 — Balanceador de Carga (IP LAN: 192.168.1.12)     "
echo "=========================================================="

JAR_PATH="Backend-Microservicios/loadbalancer-service/target/loadbalancer-service-0.0.1-SNAPSHOT.jar"

if [ ! -f "$JAR_PATH" ]; then
    echo "⚠️ No se encontró $JAR_PATH."
    echo "🔨 Compilando loadbalancer-service con Maven..."
    cd Backend-Microservicios && mvn clean package -pl loadbalancer-service -am -DskipTests && cd ..
fi

echo "🚀 Iniciando Balanceador en puerto 8000 apuntando al Coordinador en 192.168.1.13:7000..."
echo "=========================================================="

exec java -jar "$JAR_PATH" --spring.profiles.active=lan "$@"
