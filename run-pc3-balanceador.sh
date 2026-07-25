#!/usr/bin/env bash
set -e
echo "=========================================================="
echo "  PC 3 — Balanceador de Carga (IP LAN: 192.168.1.12)     "
echo "=========================================================="

JAR_DIR="Backend-Microservicios/loadbalancer-service/target"
JAR_PATH=$(find "$JAR_DIR" -maxdepth 1 -name "loadbalancer-service-*.jar" ! -name "*.original" 2>/dev/null | head -n 1 || true)

if [ -z "$JAR_PATH" ] || [ ! -f "$JAR_PATH" ]; then
    echo "⚠️ No se encontró el JAR compilado de loadbalancer-service."
    echo "🔨 Compilando loadbalancer-service con Maven..."
    cd Backend-Microservicios && mvn clean package -pl loadbalancer-service -am -DskipTests && cd ..
    JAR_PATH=$(find "$JAR_DIR" -maxdepth 1 -name "loadbalancer-service-*.jar" ! -name "*.original" 2>/dev/null | head -n 1)
fi

if [ -z "$JAR_PATH" ] || [ ! -f "$JAR_PATH" ]; then
    echo "❌ ERROR FATAL: No se pudo generar o encontrar el archivo JAR en $JAR_DIR."
    exit 1
fi

echo "🚀 Iniciando Balanceador en puerto 8000 usando: $JAR_PATH (apuntando al Coordinador en 192.168.1.13:7000)..."
echo "=========================================================="

exec java -jar "$JAR_PATH" --spring.profiles.active=lan "$@"
