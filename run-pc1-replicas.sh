#!/usr/bin/env bash
set -e
echo "=========================================================="
echo "  PC 1 — Servidor de Réplicas (IP LAN: 192.168.1.10)     "
echo "=========================================================="

JAR_DIR="Backend-Microservicios/replica-service/target"
JAR_PATH=$(find "$JAR_DIR" -maxdepth 1 -name "replica-service-*.jar" ! -name "*.original" 2>/dev/null | head -n 1 || true)

if [ -z "$JAR_PATH" ] || [ ! -f "$JAR_PATH" ]; then
    echo "⚠️ No se encontró el JAR compilado de replica-service."
    echo "🔨 Compilando replica-service con Maven..."
    cd Backend-Microservicios && mvn clean package -pl replica-service -am -DskipTests && cd ..
    JAR_PATH=$(find "$JAR_DIR" -maxdepth 1 -name "replica-service-*.jar" ! -name "*.original" 2>/dev/null | head -n 1)
fi

if [ -z "$JAR_PATH" ] || [ ! -f "$JAR_PATH" ]; then
    echo "❌ ERROR FATAL: No se pudo generar o encontrar el archivo JAR en $JAR_DIR."
    exit 1
fi

ID="$1"
if [ -z "$ID" ] || [ "$ID" = "all" ]; then
    echo "Modo de uso: ./run-pc1-replicas.sh [1|2|3]"
    echo "Por favor abre 3 terminales o pestañas en esta PC y ejecuta en cada una:"
    echo "  Terminal 1: ./run-pc1-replicas.sh 1"
    echo "  Terminal 2: ./run-pc1-replicas.sh 2"
    echo "  Terminal 3: ./run-pc1-replicas.sh 3"
    echo "=========================================================="
    exit 0
fi

shift || true

PORT=$((6000 + ID))
DB_URL="jdbc:postgresql://localhost:5432/replica_db_${ID}"

echo "🚀 Iniciando Réplica $ID en puerto $PORT usando: $JAR_PATH"
echo "📦 Base de datos: $DB_URL"
echo "👉 Para probar tolerancia a fallos en la demo, cierra esta terminal con Ctrl+C."
echo "=========================================================="

exec java -jar "$JAR_PATH" \
    --server.port="$PORT" \
    --replica.node-id="$ID" \
    --spring.datasource.url="$DB_URL" "$@"
