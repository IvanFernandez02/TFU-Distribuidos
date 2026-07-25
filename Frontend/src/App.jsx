import { useState, useEffect, useRef, useCallback } from 'react';
import './App.css';

const API = '';

function useClock() {
  const [time, setTime] = useState(new Date());
  useEffect(() => {
    const id = setInterval(() => setTime(new Date()), 1000);
    return () => clearInterval(id);
  }, []);
  return time.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

async function apiGet(path) {
  const res = await fetch(`${API}${path}`);
  return res.json();
}

async function apiPost(path, body = {}) {
  const res = await fetch(`${API}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return res.json();
}

/* ─── Tarjetas de Balanceador (192.168.1.12) y Coordinador (192.168.1.13) ─── */
function LoadBalancerCard({ aiEnabled, aiPriority }) {
  return (
    <div className="node-card node-card--coord" style={{ marginBottom: '1rem' }}>
      <div className="node-card__header">
        <div className="node-card__info">
          <h3>BALANCEADOR DE CARGA</h3>
          <span>LAN: 192.168.1.12 · PUERTO :8000</span>
        </div>
        <span className="node-status-tag node-status-tag--alive">ACTIVO</span>
      </div>
      <div className="node-card__body">
        <div className="node-card__count" style={{ fontSize: '1.15rem', color: '#7c3aed' }}>
          {aiEnabled ? 'ENRUTAMIENTO INTELIGENTE (IA)' : 'ROUTING ROUND-ROBIN'}
        </div>
        <div className="node-card__count-label" style={{ marginTop: '0.25rem' }}>
          {aiEnabled ? `Prioridad IA: ${aiPriority}` : 'Reparto equitativo hacia coordinadores'}
        </div>
      </div>
      <div className="node-card__footer">
        <span>Microservicio Spring Boot</span>
        <span>Gateway / Proxy REST</span>
      </div>
    </div>
  );
}

function CoordinatorCard({ aiEnabled }) {
  return (
    <div className="node-card node-card--coord">
      <div className="node-card__header">
        <div className="node-card__info">
          <h3>COORDINADOR QUÓRUM</h3>
          <span>LAN: 192.168.1.13 · PUERTO :7000</span>
        </div>
        <span className="node-status-tag node-status-tag--alive">ACTIVO</span>
      </div>
      <div className="node-card__body">
        <div className="node-card__count" style={{ fontSize: '1.15rem', color: '#2563eb' }}>
          {aiEnabled ? 'OLLAMA LLAMA3 / QWEN' : 'MODO DETERMINISTA'}
        </div>
        <div className="node-card__count-label" style={{ marginTop: '0.25rem' }}>
          Gestión de Heartbeat & Circuit Breaker
        </div>
      </div>
      <div className="node-card__footer">
        <span>Gifford (N=3, W=2, R=2)</span>
        <span>Escucha REST API</span>
      </div>
    </div>
  );
}

/* ─── Tarjeta de Réplica (192.168.1.10) ─── */
function ReplicaCard({ id, node, count = 0 }) {
  const alive = node ? node.alive : false;
  const state = node ? (node.state || 'CLOSED') : 'OFFLINE';

  return (
    <div className={`node-card ${alive ? 'node-card--alive' : 'node-card--dead'}`}>
      <div className="node-card__header">
        <div className="node-card__info">
          <h3>RÉPLICA {id}</h3>
          <span>LAN: 192.168.1.10 · PUERTO :{5999 + id}</span>
        </div>
        <span className={`node-status-tag ${alive ? 'node-status-tag--alive' : 'node-status-tag--dead'}`}>
          {alive ? 'VIVO' : 'CAÍDO'}
        </span>
      </div>
      <div className="node-card__body">
        <div className="node-card__count">{count}</div>
        <div className="node-card__count-label">Likes en DB Réplica {id}</div>
      </div>
      <div className="node-card__footer">
        <span>Estado: {alive ? state : 'DESCONECTADO'}</span>
        <span>PostgreSQL DB</span>
      </div>
    </div>
  );
}

/* ─── Elemento del Feed ─── */
function FeedItem({ item }) {
  const tagClassMap = {
    like: 'feed-tag--like',
    burst: 'feed-tag--burst',
    get: 'feed-tag--get',
    error: 'feed-tag--error',
    info: 'feed-tag--info'
  };
  const tagClass = tagClassMap[item.type] || 'feed-tag--info';

  return (
    <div className="feed-item">
      <span className="feed-item__time">{item.time}</span>
      <span className={`feed-tag ${tagClass}`}>{item.label || item.type.toUpperCase()}</span>
      <span className="feed-item__msg" dangerouslySetInnerHTML={{ __html: item.msg }} />
    </div>
  );
}

/* ─── Aplicación Principal ─── */
export default function App() {
  const clock = useClock();
  const [nodes, setNodes] = useState({});
  const [aiEnabled, setAiEnabled] = useState(false);
  const [aiPriority, setAiPriority] = useState('N/A');
  const [systemOk, setSystemOk] = useState(true);
  const [systemMessage, setSystemMessage] = useState('');
  const [totalLikes, setTotalLikes] = useState(0);
  const [feed, setFeed] = useState([]);
  const [loading, setLoading] = useState(false);
  const feedEndRef = useRef(null);

  const addFeed = useCallback((type, label, msg) => {
    const now = new Date();
    const time = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    setFeed(prev => [...prev.slice(-99), { id: Date.now() + Math.random(), time, type, label, msg }]);
  }, []);

  const refreshStatus = useCallback(async () => {
    try {
      const data = await apiGet('/api/status');
      if (data.ok) {
        setNodes(data.nodes || {});
        setAiEnabled(!!data.aiEnabled);
        setAiPriority(data.aiLastPriority || 'N/A');
        if (typeof data.totalLikes === 'number') {
          setTotalLikes(data.totalLikes);
        }
        const sysOk = data.systemOk !== undefined ? data.systemOk : true;
        setSystemOk(sysOk);
        setSystemMessage(data.systemMessage || 'Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente');
      } else {
        setSystemOk(false);
        setSystemMessage('Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente');
      }
    } catch {
      setSystemOk(false);
      setSystemMessage('Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente');
    }
  }, []);

  // Sondeo de estado cada 500ms
  useEffect(() => {
    refreshStatus();
    const id = setInterval(refreshStatus, 500);
    return () => clearInterval(id);
  }, [refreshStatus]);

  // Carga inicial del contador total
  useEffect(() => {
    apiGet('/api/get').then(data => {
      if (data && data.ok && typeof data.value === 'number') {
        setTotalLikes(data.value);
      }
    }).catch(() => {});
  }, []);

  // Auto-scroll para el feed
  useEffect(() => {
    feedEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [feed]);

  const getNode = (id) => nodes[String(id)] || nodes[`node${id}`];
  const node1 = getNode(1);
  const node2 = getNode(2);
  const node3 = getNode(3);

  const count1 = node1 ? (node1.likesCount || 0) : 0;
  const count2 = node2 ? (node2.likesCount || 0) : 0;
  const count3 = node3 ? (node3.likesCount || 0) : 0;

  const aliveCount = [node1, node2, node3].filter(n => n && n.alive).length;
  const isOperational = aliveCount >= 2 && systemOk;

  const doLike = async () => {
    setLoading(true);
    try {
      const data = await apiPost('/api/like');
      if (data.ok) {
        setTotalLikes(data.newValue || (totalLikes + 1));
        const targetName = data.targetNodeName ? data.targetNodeName.toLowerCase() : 'nodo';
        addFeed('like', 'LIKE', `👍 <strong>Like enviado al ${targetName}</strong>`);
      } else {
        const errStr = data.message || data.reason || 'Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente';
        addFeed('error', 'ERROR', `<span class="err-msg">❌ ${errStr}</span>`);
      }
    } catch {
      addFeed('error', 'ERROR', '<span class="err-msg">❌ Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente</span>');
    }
    setLoading(false);
    refreshStatus();
  };

  const doBurst = async (count = 100) => {
    setLoading(true);
    addFeed('info', 'INFO', `⚡ Iniciando ráfaga en vivo de <strong>${count} Likes</strong> a través del balanceador de carga...`);
    let successCount = 0;
    for (let i = 1; i <= count; i++) {
      try {
        const data = await apiPost('/api/like');
        if (data.ok) {
          successCount++;
          setTotalLikes(data.newValue || (totalLikes + successCount));
          const targetName = data.targetNodeName ? data.targetNodeName.toLowerCase() : 'nodo';
          addFeed('burst', 'BALANCEO', `⚡ [Like #${i}/${count}] Redireccionado por Balanceador -> <strong>${targetName}</strong>`);
        } else {
          const errStr = data.message || data.reason || 'Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente';
          addFeed('error', 'ERROR', `<span class="err-msg">❌ Ráfaga detenida en Like #${i}: ${errStr}</span>`);
          break;
        }
      } catch {
        addFeed('error', 'ERROR', `<span class="err-msg">❌ Ráfaga detenida en Like #${i}: Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente</span>`);
        break;
      }
      await new Promise(r => setTimeout(r, 35));
    }
    addFeed('info', 'FIN RÁFAGA', `✅ <strong>Ráfaga finalizada:</strong> ${successCount} de ${count} Likes procesados y repartidos exitosamente.`);
    setLoading(false);
    refreshStatus();
  };

  const doGet = async () => {
    setLoading(true);
    try {
      const data = await apiGet('/api/get');
      if (data.ok) {
        setTotalLikes(data.value);
        addFeed('get', 'CONSULTA', `🔄 <strong>Total sincronizado:</strong> ${data.value} Likes en el clúster.`);
      } else {
        const errStr = data.message || data.reason || 'Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente';
        addFeed('error', 'ERROR', `<span class="err-msg">❌ Consulta fallida: ${errStr}</span>`);
      }
    } catch {
      addFeed('error', 'ERROR', '<span class="err-msg">❌ Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente</span>');
    }
    setLoading(false);
    refreshStatus();
  };

  return (
    <>
      <nav className="top-nav">
        <div className="top-nav__left">
          <div className="logo-box">RSL</div>
          <div className="title-box">
            <h1>Sistema de Replicación & Quórum Distribuido</h1>
            <p>Topología LAN 4 Computadoras · Sharded Likes · Gifford (N=3, W=2, R=2)</p>
          </div>
        </div>
        <div className="top-nav__right">
          <span style={{ fontFamily: 'monospace', fontWeight: 600, color: 'var(--text-muted)' }}>{clock}</span>
          <div className={`status-badge status-badge--${isOperational ? (aliveCount === 3 ? 'ok' : 'warn') : 'error'}`}>
            <span className="status-dot" />
            {isOperational ? (aliveCount === 3 ? 'CLÚSTER OPERATIVO (3/3 NODOS)' : 'MODO DEGRADADO (2/3 NODOS)') : 'FUERA DE SERVICIO (< 2 NODOS)'}
          </div>
        </div>
      </nav>

      {!isOperational && (
        <div className="critical-alert">
          <div className="critical-alert__icon">⚠️</div>
          <div className="critical-alert__content">
            <h3>Sistema Fuera de Servicio por este momento, intentelo mas tarde nuevamente</h3>
            <p>El protocolo de Quórum de Gifford (N=3, W=2, R=2) requiere un mínimo de 2 réplicas activas para garantizar la consistencia en escrituras y lecturas. Actualmente hay menos de 2 nodos disponibles en la red.</p>
          </div>
        </div>
      )}

      <main className="dashboard-container">
        {/* TOPOLOGÍA DEL CLÚSTER */}
        <section className="panel">
          <div className="panel__title-bar">
            <h2>🖥️ Topología y Estado en Tiempo Real (Red LAN 4 Computadoras)</h2>
            <span className="panel__subtitle">Supervisión en vivo de puertos, roles y almacenamiento PostgreSQL</span>
          </div>
          <div className="topology-grid">
            <div className="coord-column">
              <LoadBalancerCard aiEnabled={aiEnabled} aiPriority={aiPriority} />
              <CoordinatorCard aiEnabled={aiEnabled} />
            </div>
            <div className="replicas-column">
              <ReplicaCard id={1} node={node1} count={count1} />
              <ReplicaCard id={2} node={node2} count={count2} />
              <ReplicaCard id={3} node={node3} count={count3} />
            </div>
          </div>
        </section>

        {/* PANEL DE ACCIONES Y CONTEO GLOBAL */}
        <section className="panel">
          <div className="panel__title-bar">
            <h2>📊 Distribución de Likes & Panel de Control</h2>
            <span className="panel__subtitle">Reparto automático entre nodos activos y consulta consolidada</span>
          </div>
          <div className="controls-layout">
            <div className="total-counter-card">
              <div className="total-counter__number">{totalLikes}</div>
              <div className="total-counter__label">LIKES TOTALES EN EL CLÚSTER</div>
              <div className="total-counter__formula">
                DB 1 ({count1}) + DB 2 ({count2}) + DB 3 ({count3}) = {totalLikes}
              </div>
            </div>

            <div className="actions-box">
              <div className="action-buttons-grid">
                <button className="btn btn--primary" onClick={doLike} disabled={loading}>
                  👍 Dar Like (+1)
                </button>
                <button className="btn btn--purple" onClick={() => doBurst(100)} disabled={loading}>
                  ⚡ Ráfaga de 100 Likes
                </button>
                <button className="btn btn--cyan" onClick={doGet} disabled={loading}>
                  🔄 Consultar Total (GET)
                </button>
              </div>
              <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)', background: 'var(--bg-inset)', padding: '0.8rem 1rem', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)' }}>
                💡 <strong>¿Cómo funciona?</strong> Al enviar Likes, el Coordinador los distribuye entre las réplicas disponibles. Cada una guarda su porción en su base de datos. Al consultar el total, las bases de datos se suman para reflejar los Likes globales del sistema.
              </p>
            </div>
          </div>
        </section>

        {/* FEED EN TIEMPO REAL */}
        <section className="panel">
          <div className="panel__title-bar">
            <h2>📢 Registro de Eventos & Notificaciones en Vivo</h2>
            <span className="panel__subtitle">Auditoría en tiempo real de peticiones, ráfagas y fallos de Quórum</span>
          </div>
          <div className="feed-container">
            {feed.length === 0 ? (
              <div className="feed-empty">
                <div style={{ fontSize: '2rem' }}>✨</div>
                <div>Sistema preparado y a la espera de interacciones.</div>
                <div style={{ fontSize: '0.8rem', opacity: 0.7 }}>Haz clic en "Dar Like (+1)" o "Ráfaga de 100 Likes" para comenzar.</div>
              </div>
            ) : (
              feed.map(item => <FeedItem key={item.id} item={item} />)
            )}
            <div ref={feedEndRef} />
          </div>
        </section>
      </main>

      <footer className="footer">
        <p><strong>Proyecto Final de Sistemas Distribuidos</strong> — Simulación de Quórum RSL con Sharding & Balanceador de Carga IA</p>
        <p style={{ marginTop: '0.25rem', opacity: 0.7 }}>Desarrollado en Linux · Java Spring Boot · React · PostgreSQL · Ollama</p>
      </footer>
    </>
  );
}
