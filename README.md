# Escenario 2 — Red Social: Replicación y Quórum (N=3, W=2, R=2)

Sistema distribuido de microservicios en Java (Spring Boot REST / TCP) y Frontend web en React que simula 3 nodos de base de datos replicando el conteo de "Likes" de publicaciones, coordinados mediante el algoritmo de cuórum de lectura/escritura de Gifford (1979). Incluye persistencia en PostgreSQL, heartbeat y circuit breaker para la detección y aislamiento de nodos caídos, más un balanceador de carga inteligente y asesor de decisiones basado en un LLM local (Llama3 / Qwen vía Ollama).

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

## Compilar y Preparar

Para compilar los microservicios e instalar las dependencias del frontend web:

```bash
# 1. Compilar los 3 microservicios del Backend (se generarán los .jar en cada target/)
cd Backend-Microservicios
mvn clean package -DskipTests
cd ..

# 2. Instalar dependencias del Frontend (Node.js / React)
cd Frontend
npm install
cd ..
```

## Ejecutar en UNA sola máquina (pruebas locales)

> [!IMPORTANT]
> Antes de ejecutar, asegúrese de tener PostgreSQL corriendo localmente con usuario `postgres` y contraseña `admin` (configurable en los `application.yml`). Cada réplica conectará de forma independiente a las bases de datos `replica_db_1`, `replica_db_2` y `replica_db_3`.

Abra 6 terminales en la carpeta raíz del proyecto para simular todo el ecosistema en local:

```bash
# Terminal 1, 2, 3: Las 3 instancias de Réplica en paralelo (puertos 6001, 6002, 6003)
java -jar Backend-Microservicios/replica-service/target/replica-service-0.0.1-SNAPSHOT.jar --server.port=6001 --replica.node-id=1 --spring.datasource.url=jdbc:postgresql://localhost:5432/replica_db_1

java -jar Backend-Microservicios/replica-service/target/replica-service-0.0.1-SNAPSHOT.jar --server.port=6002 --replica.node-id=2 --spring.datasource.url=jdbc:postgresql://localhost:5432/replica_db_2

java -jar Backend-Microservicios/replica-service/target/replica-service-0.0.1-SNAPSHOT.jar --server.port=6003 --replica.node-id=3 --spring.datasource.url=jdbc:postgresql://localhost:5432/replica_db_3

# Terminal 4: El Coordinador de Quórum (puerto 7000, apunta por defecto a localhost:6001..6003)
java -jar Backend-Microservicios/coordinator-service/target/coordinator-service-0.0.1-SNAPSHOT.jar

# Terminal 5: El Balanceador de Carga con IA (puerto 8000, redirige al coordinador :7000)
java -jar Backend-Microservicios/loadbalancer-service/target/loadbalancer-service-0.0.1-SNAPSHOT.jar

# Terminal 6: El Cliente / Servidor del Frontend Web (puerto 3001)
cd Frontend && npm run dev   # O también: node server.js
```

Una vez iniciados, abra el navegador en `http://localhost:3001` o `http://localhost:5173` para interactuar con la interfaz en tiempo real.

Para simular la caída automática de un nodo, añada `--replica.fail-after-seconds=15` al lanzar la réplica:

```bash
java -jar Backend-Microservicios/replica-service/target/replica-service-0.0.1-SNAPSHOT.jar --server.port=6003 --replica.node-id=3 --replica.fail-after-seconds=15
```

## Balanceador de carga con IA (Ollama + Llama3 / Qwen, local)

El coordinador y el balanceador consultan de forma opcional a un modelo servido localmente por Ollama para recomendar qué réplicas priorizar y sugerir transiciones del circuit breaker. Se activa por defecto en las propiedades `ai.enabled=true`. Requiere que Ollama corra en la **PC del Coordinador** (PC 2) o del Balanceador (PC 3):

```bash
# Verificar que Ollama esté corriendo
systemctl status ollama   # o: ollama serve &

# Verificar que el modelo esté descargado (ej. qwen2.5-coder:3b o llama3)
ollama list

# Probar la API manualmente
curl http://localhost:11434/api/generate -d '{"model":"qwen2.5-coder:3b","prompt":"di OK","stream":false}'
```

Si Ollama no está disponible, el sistema lo detecta automáticamente y continúa funcionando en modo 100% determinista (Round-Robin y umbral fijo de fallos), exactamente igual que si la IA estuviera apagada.

## Despliegue en las 4 PCs físicas (router/switch)

Topología configurada para el laboratorio con **4 máquinas físicas**:

| Equipo | Rol                    | Configuración y Comando de Lanzamiento                                              |
|--------|------------------------|-------------------------------------------------------------------|
| **PC 1** | **Servidor de Réplicas**<br>*(Alberga las 3 réplicas)* | En 3 terminales distintas en PC 1:<br>`java -jar replica-service.jar --server.port=6001 --replica.node-id=1`<br>`java -jar replica-service.jar --server.port=6002 --replica.node-id=2`<br>`java -jar replica-service.jar --server.port=6003 --replica.node-id=3` |
| **PC 2** | **Servidor Coordinador**<br>*(Quórum Gifford + IA)* | Redirigiendo a la IP real de PC 1 (`<IP_PC1>`):<br>`java -jar coordinator-service.jar --quorum.nodes[0].host=<IP_PC1> --quorum.nodes[1].host=<IP_PC1> --quorum.nodes[2].host=<IP_PC1>` |
| **PC 3** | **Balanceador de Carga**<br>*(Enrutador inteligente)* | Redirigiendo a la IP real de PC 2 (`<IP_PC2>`):<br>`java -jar loadbalancer-service.jar --loadbalancer.coordinators[0].url=http://<IP_PC2>:7000` |
| **PC 4** | **Cliente / Frontend Web**<br>*(Interfaz visual y ráfagas)* | Redirigiendo a la IP real de PC 3 (`<IP_PC3>`):<br>`COORD_HOST=<IP_PC3> COORD_PORT=8000 node server.js` |

### Pasos paso a paso:

1. Conectar las 4 PCs al mismo router/switch (misma subred, ej. `192.168.1.0/24`).
2. Anotar la dirección IP estática o local de cada PC (`ip addr` en Linux / `ipconfig` en Windows).
3. Copiar la carpeta del proyecto ya compilada (o compilar con `mvn package`) en las 4 PCs.
4. Verificar que el firewall/ufw de cada PC permita conexiones entrantes en los puertos correspondientes (`6001-6003` en PC 1, `7000` en PC 2, `8000` en PC 3 y `3001` en PC 4).
5. Iniciar por orden:
   - **Primero:** Las 3 instancias en **PC 1** (y verificar que PostgreSQL esté en ejecución).
   - **Segundo:** El Coordinador en **PC 2**, apuntando al host de PC 1.
   - **Tercero:** El Balanceador en **PC 3**, apuntando al host de PC 2.
   - **Cuarto:** El servidor Frontend en **PC 4**, apuntando al host de PC 3.
6. Abrir el navegador en PC 4 en `http://localhost:3001` (o desde cualquier otro dispositivo de la red apuntando a `http://<IP_PC4>:3001`).
7. **Demostración de tolerancia a fallos:** Mientras desde la interfaz web (PC 4) se genera una ráfaga de 100 Likes o peticiones continuas, detener con `Ctrl+C` cualquiera de las terminales de réplica en **PC 1**. El Coordinador detectará el fallo vía Heartbeat, aislará el nodo con el Circuit Breaker y el quórum ($W=2, R=2$) seguirá garantizando el éxito de las transacciones sin pérdida de datos.

## Evidencia y Arquitectura

El sistema se ha validado y verificado en tiempo real utilizando la arquitectura hexagonal moderna para garantizar el aislamiento entre las capas de dominio (reglas de quórum), adaptadores de entrada (controladores REST) y adaptadores de salida (IA vía Ollama y Sockets/HTTP hacia las réplicas).
