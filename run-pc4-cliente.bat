@echo off
setlocal
echo ==========================================================
echo   PC 4 — Cliente / Frontend Web (IP LAN: 192.168.1.11)   
echo ==========================================================

if not exist "Frontend\node_modules" (
    echo [ADVERTENCIA] No se encontro node_modules. Instalando dependencias con npm...
    cd Frontend
    call npm install
    cd ..
)

echo [INICIANDO] Servidor API Proxy y Dashboard React (Vite en 0.0.0.0)...
echo Accede desde esta PC en: http://localhost:5173 (o http://localhost:3001)
echo Accede desde cualquier telefono o PC LAN en: http://192.168.1.11:5173
echo ==========================================================

cd Frontend
call npm run dev:lan
endlocal
