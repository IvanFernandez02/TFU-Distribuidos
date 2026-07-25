#!/usr/bin/env bash
set -e
echo "=========================================================="
echo "  PC 2 — Servidor Coordinador (IP LAN: 192.168.1.13)     "
echo "=========================================================="

JAR_PATH="Backend-Microservicios/coordinator-service/target/coordinator-service-0.0.1-SNAPSHOT.jar"

if [ ! -f "$JAR_PATH" ]; then
    echo "⚠️ No se encontró $JAR_PATH."
    echo "🔨 Compilando coordinator-service con Maven..."
    cd Backend-Microservicios && mvn clean package -pl coordinator-service -am -DskipTests && cd ..
fi

echo "🔍 Verificando estado de Ollama IA local..."
if curl -s http://localhost:11434/api/version >/dev/null 2>&1; then
    echo "✅ Ollama IA detectado y activo en el puerto 11434."
else
    echo "ℹ️ Ollama no detectado en el puerto 11434. El Coordinador iniciará en modo 100% determinista (sin IA)."
fi

echo "🚀 Iniciando Coordinador en puerto 7000 apuntando a Réplicas en 192.168.1.10..."
echo "=========================================================="

exec java -jar "$JAR_PATH" --spring.profiles.active=lan "$@"
