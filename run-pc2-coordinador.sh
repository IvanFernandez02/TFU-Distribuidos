#!/usr/bin/env bash
set -e
echo "=========================================================="
echo "  PC 2 — Servidor Coordinador (IP LAN: 192.168.1.13)     "
echo "=========================================================="

JAR_DIR="Backend-Microservicios/coordinator-service/target"
JAR_PATH=$(find "$JAR_DIR" -maxdepth 1 -name "coordinator-service-*.jar" ! -name "*.original" 2>/dev/null | head -n 1 || true)

if [ -z "$JAR_PATH" ] || [ ! -f "$JAR_PATH" ]; then
    echo "⚠️ No se encontró el JAR compilado de coordinator-service."
    echo "🔨 Compilando coordinator-service con Maven..."
    cd Backend-Microservicios && mvn clean package -pl coordinator-service -am -DskipTests && cd ..
    JAR_PATH=$(find "$JAR_DIR" -maxdepth 1 -name "coordinator-service-*.jar" ! -name "*.original" 2>/dev/null | head -n 1)
fi

if [ -z "$JAR_PATH" ] || [ ! -f "$JAR_PATH" ]; then
    echo "❌ ERROR FATAL: No se pudo generar o encontrar el archivo JAR en $JAR_DIR."
    exit 1
fi

echo "🔍 Verificando estado de Ollama IA local..."
if curl -s http://localhost:11434/api/version >/dev/null 2>&1; then
    echo "✅ Ollama IA detectado y activo en el puerto 11434."
else
    echo "ℹ️ Ollama no detectado en el puerto 11434. El Coordinador iniciará en modo 100% determinista (sin IA)."
fi

echo "🚀 Iniciando Coordinador en puerto 7000 usando: $JAR_PATH (apuntando a Réplicas en 192.168.1.10)..."
echo "=========================================================="

exec java -jar "$JAR_PATH" --spring.profiles.active=lan "$@"
