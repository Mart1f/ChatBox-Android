# Informe Técnico Exhaustivo: ChatBox P2P

Este documento detalla la arquitectura, tecnologías y soluciones implementadas en la aplicación **ChatBox**, un sistema descentralizado de comunicación de proximidad.

---

## 🏗️ Arquitectura y Stack Tecnológico
La aplicación se ha diseñado bajo los paradigmas de desarrollo más modernos de Android (2024-2025).

- **Lenguaje:** Kotlin 2.0.21 (Motor de compilación K2), aprovechando el tipado fuerte y la síntesis de rendimiento.
- **UI:** Jetpack Compose (Material Design 3). Interfaz declarativa que elimina la necesidad de layouts XML, mejorando la coherencia del estado.
- **Build System:** Android Gradle Plugin (AGP) 9.0 con Version Catalogs (TOML) para una gestión centralizada de dependencias.

---

## 📡 Red y Protocolo P2P (Google Nearby)
El núcleo de ChatBox es la **Nearby Connections API** de Google Play Services.

### Estrategia de Conexión
- **P2P_CLUSTER:** Permite una topología de red de tipo "malla" (mesh). Los dispositivos pueden actuar como anunciantes (servidores) y descubridores (clientes) simultáneamente.
- **Descubrimiento:** Escaneo automático de redes WiFi locales, Bluetooth LE y WiFi Direct para encontrar pares sin intervención del usuario.

### Resolución de Conflictos (Tie-Breaking)
Uno de los retos técnicos más difíciles en redes P2P es el **Error 8012** (STATUS_ALREADY_CONNECTED_TO_ENDPOINT), que ocurre cuando dos nodos intentan conectarse entre sí al mismo tiempo.
- **Solución implementada:** Se asigna un `localId` único (UUID 8-char) a cada instancia. Mediante una lógica de comparación (`localId < remoteId`), solo el nodo con el ID menor tiene permitido iniciar la solicitud de conexión. El nodo con el ID mayor espera de forma pasiva. Esto garantiza un flujo de conexión determinista y libre de colisiones.

---

## 💬 Gestión de Mensajería y Datos
La aplicación soporta dos flujos de datos diferenciados sobre el mismo socket de bytes.

### Protocolo de Payloads
Los datos viajan empaquetados en una cadena estructurada:
`TYPE|SCOPE|TARGET|MSG_ID|SENDER|CONTENT`
- **Mensajería Pública:** Broadcast a todos los endpoints conectados.
- **Mensajería Directa (DM):** Filtrado selectivo en el receptor basado en el campo `TARGET`. Las DMs incluyen tracking de estado (Leído/No leído) integrado en la UI.

### Persistencia (Data Layer)
- **Motor:** SharedPreferences + Gson/JSON.
- **Implementación:** Los mensajes no son volátiles. Se serializan en tiempo real a formato JSON y se almacenan localmente. Al iniciar la app, un motor de "re-hidratación" carga los históricos en objetos `SnapshotStateList` de Compose para mantener la reactividad.

---

## 🚲 Simulación Dinámica (Proximity Simulation)
Se ha implementado una capa de lógica para demostrar la integración con sensores o proximidad real.
- **Cálculo de Proximidad:** Un slider manipula un valor virtual de distancia en metros.
- **Lógica de Mutación:** Las estaciones de bicicletas reaccionan dinámicamente, alterando su disponibilidad (`available`) mediante un algoritmo de probabilidad aleatoria cuando el usuario se encuentra dentro de un rango de 5 metros de la "estación virtual".

---

## 🛡️ Capa de Permisos y Seguridad
La app gestiona de forma exhaustiva los permisos críticos de Android 13/14+:
- `NEARBY_WIFI_DEVICES`: Para escaneo sin GPS.
- `BLUETOOTH_SCAN/CONNECT/ADVERTISE`: Para el handshake inicial.
- `ACCESS_FINE_LOCATION`: Requerido por el sistema como respaldo para metadatos de red.

---

## 📈 Resumen de Mejoras y Evolución
1. **Estabilidad:** Eliminación de desconexiones redundantes mediante el control de `pendingConnections`.
2. **UI/UX:** Consumo eficiente de recursos mediante `LazyColumn` y manejo de estados unitarios.
3. **Escalabilidad:** Separación clara entre modelos de datos (`Peer`, `ChatMessage`) y vistas.

> Este proyecto representa una implementación de referencia para aplicaciones que requieren conectividad en entornos sin infraestructura (conciertos, metro, zonas rurales).
