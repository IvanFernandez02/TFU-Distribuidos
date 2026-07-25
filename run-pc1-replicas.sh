#!/usr/bin/env bash
set -e
echo "=========================================================="
echo "  PC 1 — Servidor de Réplicas (IP LAN: 192.168.1.10)     "
echo "=========================================================="

JAR_PATH="Backend-Microservicios/replica-service/target/replica-service-0.0.1-SNAPSHOT.jar"

if [ ! -f "$JAR_PATH" ]; then
    echo "⚠️ No se encontró $JAR_PATH."
    echo "🔨 Compilando replica-service con Maven..."
    cd Backend-Microservicios && mvn clean package -pl replica-service -am -DskipTests && cd ..
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

echo "🚀 Iniciando Réplica $ID en puerto $PORT..."
echo "📦 Base de datos: $DB_URL"
echo "👉 Para probar tolerancia a fallos en la demo, cierra esta terminal con Ctrl+C."
echo "=========================================================="

exec java -jar "$JAR_PATH" \
    --server.port="$PORT" \
    --replica.node-id="$ID" \
    --spring.datasource.url="$DB_URL" "$@"
