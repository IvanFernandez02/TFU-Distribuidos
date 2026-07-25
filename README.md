# Escenario 2 — Red Social: Replicación y Quórum (N=3, W=2, R=2)

Sistema distribuido en Java (Sockets TCP) que simula 3 nodos de base de
datos replicando el conteo de "Likes" de publicaciones,
coordinados mediante el algoritmo de cuórum de lectura/escritura de
Gifford (1979). Incluye persistencia en PostgreSQL, heartbeat y circuit 
breaker para la detección y aislamiento de nodos caídos, más un asesor 
de decisiones basado en un LLM local (Llama3 vía Ollama).

## Arquitectura: Hexagonal (Puertos y Adaptadores)

El código está organizado en 4 capas, de adentro hacia afuera:

```
com.quorum.domain          -> Reglas de negocio puras (sin sockets, sin HTTP)
com.quorum.domain.port     -> Interfaces (contratos) que el dominio necesita
com.quorum.application     -> Casos de uso: orquesta el dominio a través de los puertos
com.quorum.adapters.tcp    -> "Cómo": TCP concreto (réplicas, coordinador, cliente)
com.quorum.adapters.ai     -> "Cómo": HTTP concreto hacia Ollama
com.quorum.bootstrap       -> Composition Root: arma y arranca todo
```

| Paquete / Clase | Capa | Rol |
|---|---|---|
| `domain.StoredRecord`, `domain.ReplicaStore` | Dominio | Regla Last-Write-Wins y persistencia en PostgreSQL |
| `domain.NodeHealth`, `domain.AiDecision` | Dominio | Estado de salud / circuit breaker, y recomendación de IA |
| `domain.port.ReplicaGateway` | Puerto | Contrato para hablar con una réplica (sin especificar TCP) |
| `domain.port.AiAdvisorPort` | Puerto | Contrato para consultar al asesor de IA (sin especificar Ollama) |
| `application.QuorumCoordinatorService` | Aplicación | Caso de uso: cuórum, heartbeat, circuit breaker, salvaguarda de IA |
| `adapters.tcp.TcpReplicaGateway` | Adaptador de salida | Implementa `ReplicaGateway` con Sockets TCP |
| `adapters.tcp.ReplicaNodeServer` | Adaptador de entrada | Proceso réplica: escucha TCP, delega a `ReplicaStore` |
| `adapters.tcp.CoordinatorTcpServer` | Adaptador de entrada | Escucha TCP de clientes, delega a `QuorumCoordinatorService` |
| `adapters.tcp.ClientSimulator` | Adaptador conductor | Simula usuarios concurrentes dando Like |
| `adapters.ai.OllamaAiAdapter` | Adaptador de salida | Implementa `AiAdvisorPort` con HTTP hacia Ollama |
| `bootstrap.CoordinatorMain` | Composition Root | Lee la config, crea los adaptadores concretos y los inyecta en el caso de uso |

**Patrones de diseño aplicados:**
- **Mediator** — `QuorumCoordinatorService` es el único que conoce a las 3 réplicas.
- **Circuit Breaker** (Nygard) — máquina de estados CLOSED/OPEN/HALF_OPEN en `NodeHealth`.
- **Strategy** (vía puertos) — `ReplicaGateway` y `AiAdvisorPort` permiten cambiar la
  implementación (TCP, HTTP, mock) sin tocar la lógica de negocio.
- **Dependency Injection manual / Composition Root** — `CoordinatorMain` es el único
  lugar que hace `new TcpReplicaGateway(...)` o `new OllamaAiAdapter(...)`; el resto
  del código solo depende de las interfaces `ReplicaGateway` / `AiAdvisorPort`.

## Compilar

```bash
# Compilar las clases en el directorio bin
javac -d bin $(find src -name "*.java")
```

## Ejecutar en UNA sola máquina (pruebas locales)

> [!IMPORTANT]
> Antes de ejecutar, asegúrese de tener PostgreSQL corriendo localmente y de editar las credenciales (usuario/contraseña) en `config/replicas.properties`. Cada nodo réplica intentará crear de forma automática su base de datos (`replica_db_1`, `replica_db_2`, `replica_db_3`) si la cuenta de PostgreSQL configurada tiene permisos de creación de DB.

```bash
# Terminal 1, 2, 3: los tres nodos réplica (incluyendo el driver de Postgres en el classpath)
java -cp bin:lib/postgresql-42.7.3.jar com.quorum.adapters.tcp.ReplicaNodeServer 1 6001
java -cp bin:lib/postgresql-42.7.3.jar com.quorum.adapters.tcp.ReplicaNodeServer 2 6002
java -cp bin:lib/postgresql-42.7.3.jar com.quorum.adapters.tcp.ReplicaNodeServer 3 6003

# Terminal 4: el coordinador (lee config/replicas.properties)
java -cp bin com.quorum.bootstrap.CoordinatorMain config/replicas.properties

# Terminal 5: el cliente / generador de carga
java -cp bin com.quorum.adapters.tcp.ClientSimulator 127.0.0.1 7000 like post123 100 10
java -cp bin com.quorum.adapters.tcp.ClientSimulator 127.0.0.1 7000 get post123
java -cp bin com.quorum.adapters.tcp.ClientSimulator 127.0.0.1 7000 status
```

Para simular la caída de un nodo automáticamente (sin apagarlo a mano),
agregue `--fail-after=SEGUNDOS` al lanzar el `ReplicaNodeServer`, por ejemplo:

```bash
java -cp bin com.quorum.adapters.tcp.ReplicaNodeServer 3 6003 --fail-after=15
```

Esto cierra el `ServerSocket` de ese nodo pasados 15 segundos, simulando
una caída real (crash) sin necesidad de matar el proceso manualmente.

## Balanceador de carga con IA (Ollama + Llama3, local)

El coordinador incluye un adaptador opcional (`OllamaAiAdapter`, detrás del
puerto `AiAdvisorPort`) que consulta a un modelo Llama3 servido localmente
por Ollama para recomendar qué réplicas priorizar y sugerir transiciones
del circuit breaker. Se activa con `ai.enabled=true` en
`config/replicas.properties` (activado por defecto). Requiere que Ollama
corra en la **misma PC que el coordinador** (PC4), con el modelo `llama3`
ya descargado:

```bash
# Verificar que Ollama esté corriendo
systemctl status ollama   # o: ollama serve &

# Verificar que el modelo esté descargado
ollama list

# Probar la API manualmente
curl http://localhost:11434/api/generate -d '{"model":"llama3","prompt":"di OK","stream":false}'
```

Si Ollama no está disponible, `OllamaAiAdapter.consult()` devuelve `null`
y el coordinador sigue funcionando en modo 100% determinista (heartbeat +
umbral fijo de fallos), exactamente igual que si `ai.enabled=false`. Esto
se puede comprobar en `logs/coordinator.log`, donde cada ronda sin
respuesta de Ollama se registra como "Asesor de IA no disponible en esta
ronda". Para deshabilitar la IA por completo, basta con poner
`ai.enabled=false`.

## Despliegue en las 5 PCs físicas (router/switch)

Topología recomendada para el laboratorio:

| Equipo | Rol                    | Comando a ejecutar                                              |
|--------|------------------------|-------------------------------------------------------------------|
| PC1    | Nodo réplica 1         | `java -cp bin:lib/postgresql-42.7.3.jar com.quorum.adapters.tcp.ReplicaNodeServer 1 6001`   |
| PC2    | Nodo réplica 2         | `java -cp bin:lib/postgresql-42.7.3.jar com.quorum.adapters.tcp.ReplicaNodeServer 2 6002`   |
| PC3    | Nodo réplica 3         | `java -cp bin:lib/postgresql-42.7.3.jar com.quorum.adapters.tcp.ReplicaNodeServer 3 6003`   |
| PC4    | Coordinador            | `java -cp bin com.quorum.bootstrap.CoordinatorMain config/replicas.properties` |
| PC5    | Cliente / generador    | `java -cp bin com.quorum.adapters.tcp.ClientSimulator <IP_PC4> 7000 like post123 200 20` |

Pasos:

1. Conectar las 5 PCs al mismo router/switch (misma subred, ej. `192.168.1.0/24`).
2. Anotar la IP de cada PC (`ip addr` en Linux / `ipconfig` en Windows).
3. Copiar la carpeta del proyecto a las 5 PCs (o compartir por USB/red).
4. En **PC4**, editar `config/replicas.properties` y reemplazar
   `127.0.0.1` por la IP real de PC1, PC2 y PC3:

   ```properties
   node1.host=192.168.1.11
   node2.host=192.168.1.12
   node3.host=192.168.1.13
   ```

5. Verificar que el firewall de cada PC permita conexiones entrantes en
   los puertos usados (6001-6003 en los nodos, 7000 en el coordinador).
6. Iniciar primero los 3 nodos réplica (PC1-PC3), luego el coordinador
   (PC4) y finalmente el cliente (PC5) apuntando a la IP de PC4.
7. Para la demostración de tolerancia a fallos: mientras el cliente
   (PC5) está generando likes, cerrar con `Ctrl+C` el proceso
   `ReplicaNodeServer` en una de las PC1-PC3. El coordinador debe seguir
   aceptando LIKE/GET usando los 2 nodos restantes (W=2, R=2), lo cual
   queda registrado en `logs/coordinator.log` en PC4.

## Evidencia de pruebas

`docs/evidencia_ejecucion_coordinador.log` contiene el log completo de
una ejecución de prueba: 100 likes con los 3 nodos activos, caída
programada del nodo 3, detección por heartbeat, apertura del circuit
breaker, y 100 likes adicionales exitosos usando solamente 2 de los 3
nodos (evidenciando que el cuórum W=2/R=2 tolera la caída de 1 nodo).
Esta evidencia corresponde a la versión previa del código (clases sin
paquete); el comportamiento verificado es idéntico en la versión actual
con arquitectura hexagonal, ya que solo cambió la organización de las
clases, no la lógica.
