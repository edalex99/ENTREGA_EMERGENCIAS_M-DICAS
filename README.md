# Sistema de Gestión de Emergencias Médicas con Concurrencia

**S30 - EA2 | IUDigital de Antioquia | Java 17+ (probado con JDK 21)**

Simulador académico en consola del caso de estudio proporcionado. Gestiona llamadas simultáneas, clasifica por gravedad/espera/proximidad, reserva conjuntamente una ambulancia, un médico y un equipo, envía mensajes al hospital, presenta un monitor concurrente y simula averías con reintento y reparación. **No constituye software clínico ni integra servicios públicos de emergencias.**

## 1. Requisitos previos

- Java Development Kit (JDK) 17 o superior: `java -version` y `javac -version`.
- PowerShell 5.1+ en Windows o bash en Linux/macOS.
- Maven es **opcional** para compilar (`pom.xml`). Las pruebas funcionan sin dependencias ni conexión a internet.

## 2. Ejecución rápida

En Windows, dentro de la carpeta raíz del proyecto:

```powershell
java -version
javac -version
.\scripts\compilar_y_ejecutar.ps1 --scenario=demo --calls=14 --interval-ms=1000
```

Si la política de PowerShell impide ejecutar scripts:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\compilar_y_ejecutar.ps1 --scenario=demo
```

En Linux/macOS:

```bash
bash scripts/compilar_y_ejecutar.sh --scenario=demo --calls=14
```

También se puede compilar con Maven (`mvn package`) y ejecutar directamente:

```powershell
java -cp target/classes edu.iudigital.emergencias.Main --scenario=demo
```

## 3. Escenarios y comandos útiles

| Escenario | Comando PowerShell | Propósito |
|---|---|---|
| Demostración | `.\scripts\compilar_y_ejecutar.ps1 --scenario=demo` | Ver monitoreo, llamadas, despacho y comunicación |
| Prioridad | `.\scripts\compilar_y_ejecutar.ps1 --scenario=priority --calls=20` | Comparar críticos y leves en espera |
| Alta carga | `.\scripts\compilar_y_ejecutar.ps1 --scenario=stress --calls=120 --speed=5 --no-monitor --quiet` | Comparar métricas con 20/60/120 llamadas |
| Fallos | `.\scripts\compilar_y_ejecutar.ps1 --scenario=failure --calls=8 --failure-rate=0.65 --interval-ms=500` | Ver reencolado, ambulancia fuera de servicio y reparación |
| Ayuda | `.\scripts\compilar_y_ejecutar.ps1 --help` | Ver argumentos |

Parámetros: `--calls=N`, `--ambulances=N`, `--medics=N`, `--kits=N`, `--failure-rate=0..1`, `--speed=N`, `--seed=N`, `--interval-ms=N`, `--timeout-sec=N`, `--no-monitor`, `--quiet`.

> Las salidas exactas pueden variar por la planificación de hilos y la temporización. Las ubicaciones son **sintéticas** y el tiempo de viaje es simulado; no se realizan solicitudes a servicios externos.

## 4. Pruebas automatizadas

```powershell
.\scripts\ejecutar_pruebas.ps1
```

O en Linux/macOS: `bash scripts/ejecutar_pruebas.sh`.

Incluye ocho pruebas: distancia; priorización por gravedad; proximidad; envejecimiento; exclusión mutua frente a 12 despachadores; 50 llamadas concurrentes; agotamiento de reintentos y reparación; validación de entradas. Se ejecutan sin JUnit, con aserciones explícitas. Un fallo termina la ejecución con excepción, no devuelve un falso positivo.

## 5. Arquitectura

```text
Generador -> ArrayBlockingQueue(250) -> Operadores (ExecutorService)
                                          |
                                          v
                                    Coordinador (monitor)
                                    - Cola pendiente (ArrayList bajo lock)
                                    - Política de prioridad dinámica
                                    - Reserva atómica de 3 recursos
                                          |
                            Despachadores (ExecutorService)
                                          |
                             Ambulancias (ExecutorService)
                                |                 |
                  Mensajes hospital        Reparaciones programadas
                  (BlockingQueue)          (ScheduledExecutorService)
                               \               /
                              Monitoreo (snapshot protegido)
```

**Decisión clave:** no se utiliza `PriorityBlockingQueue` con comparador que depende del reloj (rompería el orden del heap). El coordinador recalcula la prioridad de las llamadas pendientes en cada intento de despacho, utilizando un único candado para la elección y la reserva indivisible de ambulancia, médico y equipo. Las esperas de viaje, los mensajes y la salida de consola se producen fuera de esa sección crítica.

La prioridad es: `puntos(gravedad) + 15 × espera_en_segundos − 10 × distancia_km`. Los puntos son crítica=1000, grave=700, moderada=400 y leve=100. Estos coeficientes son decisiones de **simulación académica**, no criterios clínicos validados. Cada emergencia necesita un recurso de cada tipo. Una llamada no se despacha hasta que existe un paquete completo.

### Estados

- Emergencia: `REGISTRADA -> EN_ESPERA -> ASIGNADA -> EN_CAMINO -> EN_ATENCION -> COMPLETADA`.
- Ante fallo de traslado: `EN_CAMINO -> EN_ESPERA` (si quedan intentos) o `EN_CAMINO -> FALLIDA` (si se agotaron).
- Ambulancia: `DISPONIBLE -> EN_CAMINO -> OCUPADO -> DISPONIBLE`; fallo: `EN_CAMINO -> FUERA_DE_SERVICIO -> DISPONIBLE`.
- Personal/equipo: `DISPONIBLE <-> OCUPADO`.

## 6. Métricas e interpretación

- `promedio_asignacion`: desde la creación de una llamada hasta la primera asignación, considerando casos terminados.
- `promedio_resolucion`: desde la creación hasta completarse o declararse fallida.
- `utilizacion_ambulancias_aprox`: suma de tiempos de reserva dividida entre número de ambulancias y duración total observada; simulación, no productividad clínica.
- `maxConcurrent`: asignaciones activas al mismo tiempo (limitadas por los recursos).
- `espera_max_cola`: máximo de emergencias simultáneamente pendientes.

El monitor presenta cada segundo, por defecto, estados de ambulancias, médicos, equipos, llamadas y los cinco casos pendientes con mayor puntaje.

## 7. Estructura de la entrega

- `src/main/java/edu/iudigital/emergencias/`: implementación comentada.
- `src/test/java/edu/iudigital/emergencias/ProjectTests.java`: pruebas.
- `scripts/`: ejecutar y probar, tanto Windows como Linux/macOS.
- `diagramas/`: fuentes Graphviz DOT y diagramas PNG.
- `evidencias/`: transcripciones auténticas de corridas reales, capturas ilustradas a partir de dichas transcripciones y resultados de pruebas.
- `docs/`: documento técnico APA 7 y guion de video.
- `EQUIPO.md`: plantilla para documentar contribuciones verificables.

## 8. Publicar en GitHub

Desde la raíz del proyecto (crear antes un repositorio vacío en GitHub):

```powershell
git init
git add .
git commit -m "Implementación: simulador concurrente de emergencias médicas"
git branch -M main
git remote add origin https://github.com/USUARIO/NOMBRE_REPOSITORIO.git
git push -u origin main
```

Reemplazar **USUARIO/NOMBRE_REPOSITORIO** por el repositorio real. No ejecutar `git remote add origin` si ya existe un remoto. Adjuntar en la plataforma el enlace real, el documento técnico y el enlace de video de YouTube.

## 9. Limitaciones y decisiones deliberadas

- Simulación en memoria: un reinicio pierde los incidentes y los mensajes; para producción se necesita persistencia transaccional y mensajería durable.
- La comunicación con hospital es un actor local, **no** una conexión de red ni un sistema hospitalario real.
- No hay geolocalización real, calles, tiempos de tráfico, interoperabilidad clínica ni datos de pacientes.
- Una avería simula indisponibilidad de ambulancia/traslado; el máximo de tres intentos limita reintentos indefinidos.
- La cola de ingreso tiene capacidad 250: en saturación, `submit` ofrece durante hasta dos segundos y reporta explícitamente rechazo si está llena.
- Las métricas de carga dependen del hardware y ejecución; comparar resultados en el mismo equipo y configuración.
- La interrupción forzada ante un timeout requiere cierre del motor; el uso normal es esperar `FINALIZADO`.

## 10. Fuentes de la actividad

- *Caso de Estudio - Sistema de Gestión de Emergencias Médicas* (material de la actividad, s. f.).
- *S30 - EA2: Sistema de Gestión de Emergencias Médicas con Concurrencia* (guía de la actividad, s. f.).
