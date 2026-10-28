# Verificación de la entrega S30–EA2

| Criterio solicitado | Estado y soporte concreto |
|---|---|
| Aplicación Java que procesa simultáneamente emergencias | **Implementado y probado**: `SimulationEngine.java`, colas y pools |
| Acceso concurrente seguro a ambulancias, médicos y equipos | **Implementado y probado**: `DispatchCoordinator.java`, prueba con 12 despachadores |
| Prioridad multivariable gravedad, espera y ubicación | **Implementado y probado**: `PriorityPolicy.java`, `GeoPoint.java` |
| Patrones de diseño concurrente | **Documentado**: productor-consumidor, monitor, paso de mensajes |
| Comunicación entre operadores, despachadores, ambulancias y hospital | **Implementado**: `Communications.java` y eventos de consola |
| Monitoreo periódico de colas, recursos y métricas | **Implementado**: `ConsoleMonitor.java` y capturas en `evidencias/` |
| Tratamiento de fallos y reintentos | **Implementado y probado**: avería, reencolado, reparación y límite de intentos |
| Código fuente completo e instrucciones | **Listo localmente para publicar**: `src/`, `README.md`, `pom.xml`, `scripts/` |
| Informe técnico de 8 a 10 páginas, APA, imágenes, rendimiento | **Verificado**: informe técnico ampliado de 10 páginas en `docs/Informe_Tecnico_Ampliado_APA7_S30_EA2.docx` y `.pdf`, con 7 figuras y resultados de carga |

## Evidencias disponibles

- `pruebas_automatizadas.txt`: ocho pruebas superadas.
- `ejecucion_demo.txt`: catorce emergencias completadas.
- `ejecucion_estres_60.txt`, `rendimiento_20.txt`, `rendimiento_120.txt`: ejecuciones comparables de rendimiento.
- `ejecucion_fallos.txt`: fallos simulados con reintentos, reparación y límite de intentos.
- `captura_*.png`: representación fiel de fragmentos de salida de consola guardados en los registros anteriores.