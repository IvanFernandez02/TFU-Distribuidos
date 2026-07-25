#!/usr/bin/env bash
set -e

echo "=========================================================="
echo "  Construyendo Ecosistema RSL (Microservicios + React)   "
echo "=========================================================="

echo ""
echo "1) Compilando Backend Microservicios (Maven)..."
cd Backend-Microservicios
mvn clean package -DskipTests
cd ..

echo ""
echo "2) Instalando dependencias del Frontend (npm)..."
cd Frontend
npm install
cd ..

echo ""
echo "✅ ¡Todo construido con éxito y listo para el laboratorio LAN!"
echo "=========================================================="
