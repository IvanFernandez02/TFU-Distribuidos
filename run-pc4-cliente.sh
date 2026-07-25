#!/usr/bin/env bash
set -e
echo "=========================================================="
echo "  PC 4 — Cliente / Frontend Web (IP LAN: 192.168.1.11)   "
echo "=========================================================="

if [ ! -d "Frontend/node_modules" ]; then
    echo "⚠️ No se encontró la carpeta node_modules en Frontend/."
    echo "📦 Instalando dependencias del Dashboard React con npm..."
    cd Frontend && npm install && cd ..
fi

echo "🌐 Iniciando Servidor API Proxy y Dashboard React (Vite escuchando en 0.0.0.0)..."
echo "👉 Accede desde esta PC en: http://localhost:5173 (o http://localhost:3001)"
echo "👉 Accede desde cualquier teléfono o PC LAN en: http://192.168.1.11:5173"
echo "=========================================================="

cd Frontend && npm run dev:lan
