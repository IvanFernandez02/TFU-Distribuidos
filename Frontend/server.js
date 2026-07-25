import express from 'express';
import cors from 'cors';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const app = express();
app.use(cors());
app.use(express.json());

const COORDINATOR_HOST = process.env.COORD_HOST || '127.0.0.1';
// Apuntamos por defecto al puerto 8000 (Load Balancer con IA) para que balancee el tráfico hacia el Coordinador
const COORDINATOR_PORT = parseInt(process.env.COORD_PORT || '8000');
const COORDINATOR_BASE = `http://${COORDINATOR_HOST}:${COORDINATOR_PORT}/api`;

// ============================================================================
// Antes: se usaba un Socket TCP (net.Socket) para hablar con el coordinador.
// Ahora: el coordinador es un microservicio Spring Boot con API REST, así que
// simplemente se hacen llamadas HTTP con fetch(). Ya no se necesita el módulo
// "net" de Node.js.
// ============================================================================

// GET /api/status
app.get('/api/status', async (req, res) => {
  try {
    const response = await fetch(`${COORDINATOR_BASE}/status`);
    const data = await response.json();
    res.json(data);
  } catch (err) {
    res.status(503).json({ 
      ok: false, 
      error: "Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente",
      systemMessage: "Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente",
      nodes: {}
    });
  }
});

// POST /api/like
app.post('/api/like', async (req, res) => {
  try {
    const response = await fetch(`${COORDINATOR_BASE}/like`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req.body || {})
    });
    const data = await response.json();
    res.json(data);
  } catch (err) {
    res.status(503).json({ 
      ok: false, 
      error: "Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente",
      message: "Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente"
    });
  }
});

// GET /api/get & /api/get/:postId
app.get(['/api/get', '/api/get/:postId'], async (req, res) => {
  try {
    const response = await fetch(`${COORDINATOR_BASE}/get`);
    const data = await response.json();
    res.json(data);
  } catch (err) {
    res.status(503).json({ 
      ok: false, 
      error: "Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente",
      message: "Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente"
    });
  }
});

// POST /api/burst — simulate N concurrent likes
app.post('/api/burst', async (req, res) => {
  const { count = 100, concurrency = 10 } = req.body || {};
  try {
    const response = await fetch(`${COORDINATOR_BASE}/burst`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ count, concurrency })
    });
    const data = await response.json();
    res.json(data);
  } catch (err) {
    res.status(503).json({ 
      ok: false, 
      error: "Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente",
      message: "Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente"
    });
  }
});

// Serve static build in production
app.use(express.static(join(__dirname, 'dist')));
app.get('*', (req, res) => {
  res.sendFile(join(__dirname, 'dist', 'index.html'));
});

const PORT = process.env.PORT || 3001;
app.listen(PORT, () => {
  console.log(`\n  ╔══════════════════════════════════════════╗`);
  console.log(`  ║  RSL Quorum Dashboard — API Server       ║`);
  console.log(`  ║  http://localhost:${PORT}                  ║`);
  console.log(`  ║  Load Balancer (IA): ${COORDINATOR_BASE} ║`);
  console.log(`  ╚══════════════════════════════════════════╝\n`);
});
