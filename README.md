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

## Despliegue en Red Local LAN (4 PCs físicas)

Topología configurada y pre-armada para el laboratorio con **4 máquinas físicas LAN**:
- **PC 1 (Servidor de Réplicas)**: `192.168.1.10`
- **PC 2 (Servidor Coordinador + Ollama IA)**: `192.168.1.13`
- **PC 3 (Servidor Balanceador de Carga)**: `192.168.1.12`
- **PC 4 (Cliente / Frontend Web React)**: `192.168.1.11`

> [!CAUTION]
> **Conexión por Cable Ethernet a Switch (Sin Router / DHCP)**:
> Al conectar las 4 computadoras mediante cables de red directos a un **Switch no administrado**, el switch no asignará direcciones IP automáticamente. Debes realizar lo siguiente antes de ejecutar los servicios:
> 1. **Configurar IP Estática / Manual en el adaptador Ethernet (`eth0` / `enp...`)**: En cada PC, entra a la configuración de red por cable (Ajustes de Red en Linux o Network Adapter en Windows), selecciona **Manual (Estático)** y asigna la dirección IPv4 correspondiente (`192.168.1.10`, `.11`, `.12`, `.13`) con Máscara de Subred `255.255.255.0` (o `/24`).
> 2. **Verificar cableado con `ping`**: Antes de abrir Java o Node, abre una terminal en la PC Cliente (`192.168.1.11`) y comprueba la comunicación a nivel de enlace de datos:
>    ```bash
>    ping -c 3 192.168.1.10   # Probar hacia PC Réplicas
>    ping -c 3 192.168.1.13   # Probar hacia PC Coordinador
>    ping -c 3 192.168.1.12   # Probar hacia PC Balanceador
>    ```
> 3. **Firewall en tarjeta de red**: Asegúrate de que el cortafuegos permita conexiones entrantes por la interfaz cableada o desactívalo temporalmente en el laboratorio (`sudo ufw disable` en Linux o desactivar Firewall en red privada en Windows).

Gracias a los nuevos scripts automatizados en la raíz del proyecto (`.sh` para Linux/macOS y `.bat` para Windows), tus compañeros solo deben hacer `git pull`, abrir una terminal en la **carpeta raíz** y ejecutar el script que le corresponde a su PC:

| Equipo | Rol & IP LAN | Comando de Ejecución (¡Directo desde la carpeta raíz!) |
|---|---|---|
| **PC 1** | **Servidor de Réplicas**<br>`192.168.1.10` | Abre 3 terminales o pestañas en la carpeta raíz y ejecuta en cada una:<br>`./run-pc1-replicas.sh 1`<br>`./run-pc1-replicas.sh 2`<br>`./run-pc1-replicas.sh 3`<br>*(En Windows usa `run-pc1-replicas.bat 1`, etc.)* |
| **PC 2** | **Servidor Coordinador**<br>`192.168.1.13` | En 1 terminal en la carpeta raíz:<br>`./run-pc2-coordinador.sh`<br>*(En Windows usa `run-pc2-coordinador.bat`)* |
| **PC 3** | **Balanceador de Carga**<br>`192.168.1.12` | En 1 terminal en la carpeta raíz:<br>`./run-pc3-balanceador.sh`<br>*(En Windows usa `run-pc3-balanceador.bat`)* |
| **PC 4** | **Cliente / Frontend Web**<br>`192.168.1.11` | En 1 terminal en la carpeta raíz:<br>`./run-pc4-cliente.sh`<br>*(En Windows usa `run-pc4-cliente.bat`)* |

> [!TIP]
> Los scripts detectan si el código ya fue compilado con Maven o si falta `node_modules`. Si no se han construido, ¡los compilan e instalan automáticamente antes de lanzar el servicio! Si desean construir todo de antemano de un solo golpe, pueden ejecutar `./build-all.sh` (o `build-all.bat`).

### Pasos paso a paso para el grupo:

1. Conectar las 4 PCs al Switch con los cables Ethernet.
2. Confirmar que cada máquina tenga asignada manualmente la IP estática correspondiente (`192.168.1.10`, `.11`, `.12`, `.13`).
3. Hacer un `git pull` en las 4 computadoras para descargar estos scripts actualizados en la raíz.
4. Verificar con `ping` que las PCs se ven entre sí por el switch y que el firewall (`ufw` / Firewall de Windows) permita el tráfico.
5. Iniciar los scripts por orden desde la carpeta raíz:
   - **Paso 1 (PC 1 - `192.168.1.10`):** Ejecutar los 3 scripts de réplicas en sus terminales (verificando que PostgreSQL esté corriendo).
   - **Paso 2 (PC 2 - `192.168.1.13`):** Ejecutar `./run-pc2-coordinador.sh`.
   - **Paso 3 (PC 3 - `192.168.1.12`):** Ejecutar `./run-pc3-balanceador.sh`.
   - **Paso 4 (PC 4 - `192.168.1.11`):** Ejecutar `./run-pc4-cliente.sh`.
6. **Interactuar con el sistema:** Abrir el navegador desde PC 4 entrando a `http://localhost:5173` (o desde el teléfono o cualquier otra PC de la red entrando a `http://192.168.1.11:5173`).
7. **Demostración de tolerancia a fallos en vivo:** Al iniciar una ráfaga de 100 Likes desde la interfaz web, desconectar o cerrar con `Ctrl+C` cualquiera de las terminales en PC 1 (`192.168.1.10`). Se observará de inmediato en el panel de auditoría visual y en los logs cómo el Heartbeat en PC 2 cambia el estado a **OPEN**, el Circuit Breaker aísla el nodo caído, y el sistema sigue procesando el tráfico con las 2 réplicas restantes garantizando el quórum ($W=2, R=2$).

## Evidencia y Arquitectura

El sistema se ha validado y verificado en tiempo real utilizando la arquitectura hexagonal moderna para garantizar el aislamiento entre las capas de dominio (reglas de quórum), adaptadores de entrada (controladores REST) y adaptadores de salida (IA vía Ollama y Sockets/HTTP hacia las réplicas).
