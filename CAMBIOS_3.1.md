# COCOTAXI 3.1 Core — cambios y auditoría

Fecha: 24 de septiembre de 2026

## Base y alcance

COCOTAXI 3.1 Core se reconstruye exclusivamente desde la rama `archive/cocotaxi-2.7.1-known-good`, commit base `8b221441eecdb0ce1684b82704a17fc762588414`.

La candidata auditada final es **3.1.2** (`versionCode 52`). Esta etapa modifica únicamente el núcleo operativo de Coco. No implementa todavía la nueva arquitectura Data, Atlas, Learning ni Advisor.

Quedan expresamente fuera de 3.1 Core:

- carpeta y retención mensual de datos de 3.1 Data;
- Atlas geográfico de Montevideo;
- `HeatMapCells MVD_*`;
- alineación geográfica pantalla ↔ Atlas;
- aprendizaje por celda/día/hora;
- valor futuro del destino;
- pantalla nueva DÓNDE IR y estimación estadística por zona.

La lógica de mapa heredada de 2.7.1 puede seguir existiendo en el proyecto, pero **no debe considerarse la implementación de 3.2 Atlas**.

## Cambios de Core

### Relojes de sesión y servicio

- `En servicio` usa tiempo real y deja de estar limitado por los minutos estimados de la oferta.
- Pausar Coco congela tanto el tiempo conectado como el segmento activo de servicio.
- Reanudar crea una nueva ancla monotónica; el tiempo de pausa no se añade después al viaje.
- Desconectar guarda primero el segmento de servicio acumulado antes de invalidar un viaje pendiente.
- El servicio activo se checkpointa junto con la sesión aproximadamente cada 5 segundos. Esto limita la pérdida máxima normal de tiempo ante una muerte abrupta del proceso.
- Las duraciones normales usan `SystemClock.elapsedRealtime()` dentro del mismo arranque de Android para no depender de cambios de la hora del sistema.

### Recuperación tras cierre del proceso o reinicio

Se añadió un estado persistente de recuperación mediante `sessions.recovering`.

Al abrir un proceso nuevo, `CocoStore.get()` arma la recuperación antes de permitir que una lectura normal de `session()` avance los relojes. Esto evita una carrera en la que la Activity podía abrir antes de `AccessibilityService.onServiceConnected()`.

Cuando existe un viaje `ASIGNADO` persistido:

1. Coco congela sesión y servicio en el último checkpoint persistido.
2. Marca la sesión como `recovering`.
3. Accessibility lee la pantalla real de Cabify.
4. Si Cabify muestra viaje/recogida/acción Finalizar, Coco recupera `EN SERVICIO` desde el tiempo guardado.
5. Si Cabify muestra de forma fuerte el mapa de búsqueda, el viaje queda `SIN CONFIRMAR` y Coco vuelve a buscar sin crear servicio fantasma.
6. Si aparece evidencia explícita de cancelación o finalización, se resuelve el viaje según esa evidencia.
7. Una pausa manual no se confunde con recuperación de crash.

El tiempo durante el que Coco estuvo muerto o el teléfono estuvo reiniciándose **no se inventa como servicio**. La última marca persistida es la referencia conservadora hasta reconciliar Cabify.

### Finalización y cancelación

La finalización ya no depende de una sola cadena o de un solo tipo de evento. Se usan señales separadas para:

- interacción con `Finalizar` / variantes de la acción;
- texto explícito de viaje completado;
- regreso estable al mapa principal real de búsqueda;
- transición desde la etapa final hacia la recogida de un siguiente viaje ya aceptado.

La cancelación se trata aparte: detiene el reloj sin convertir automáticamente el viaje en un viaje finalizado cobrado.

Un mismo viaje sólo puede pasar de `ASIGNADO` a finalizado una vez.

### Siguiente viaje

Se añadieron estados separados:

- `INTENTO_SIGUIENTE`
- `SIGUIENTE`

Mientras existe un viaje `ASIGNADO`, Coco puede registrar y confirmar un único siguiente viaje. Ese segundo viaje no inicia un segundo reloj de servicio mientras el actual sigue activo.

Cuando el viaje actual termina o se cancela, el `SIGUIENTE` de **la misma sesión** se promociona a `ASIGNADO` y comienza su ancla de servicio.

### Detección de aceptación manual

Se eliminó el booleano global `assignmentSeen`.

La deduplicación depende ahora del viaje activo y de las evidencias recientes de la oferta, evitando que el estado de una aceptación anterior pueda bloquear una aceptación manual posterior.

Si Cabify cambia de pantalla demasiado rápido, Coco puede reutilizar sólo una oferta individual reciente y suficientemente completa. No reutiliza una lista ambigua.

### Parser monetario

El parser evalúa cada importe individualmente en lugar de descartar una línea completa por contener `/h` o `/km`.

Se priorizan importes adyacentes a:

- `para ti`
- `en app`

y se penalizan importes asociados a:

- `/km`
- `/h`
- saldo;
- billetera;
- ganancias/resúmenes no pertenecientes a la oferta.

Existe una regresión automatizada basada en el caso real:

`$392 para ti · $39/km · $1.383/h`

y exige que el importe de la oferta sea **392**.

### Rendimiento y Accessibility

- Accessibility sigue siendo la ruta principal y más rápida.
- El árbol se protege mediante `safeInspect()` para que un nodo que desaparece durante la lectura no derribe el flujo completo.
- El sondeo de respaldo deja de ejecutarse cada 100 ms:
  - sesión activa: aproximadamente 1,2 s;
  - pausa: aproximadamente 3 s;
  - sin sesión: aproximadamente 5 s.
- Los eventos de Accessibility siguen disparando inspección inmediata; esos intervalos son sólo respaldo.
- Se habilita `flagIncludeNotImportantViews`.
- El OCR continúa siendo respaldo y no sustituye el camino rápido de Accessibility.
- La captura pasiva se limita y usa una huella visual/región útil para evitar OCR repetido sobre frames prácticamente iguales.
- Una oferta incompleta puede solicitar una captura urgente sin esperar el ciclo pasivo.

## Correcciones encontradas durante la auditoría

La auditoría posterior a 3.1.0 no se limitó a comprobar que compilara. Encontró y corrigió estos problemas antes de considerar Core congelable:

1. El estado global `assignmentSeen` podía interferir con una aceptación posterior.
2. Una recuperación inicial podía contar tiempo de reinicio como servicio.
3. Desconectar durante un viaje no guardaba primero el segmento activo.
4. La promoción del siguiente viaje no estaba restringida explícitamente a la sesión actual.
5. La expiración de intentos podía cambiar SQLite sin invalidar inmediatamente las cachés en memoria.
6. La primera estrategia de recuperación no cubría correctamente una muerte del proceso dentro del mismo arranque de Android.
7. Existía una carrera si la Activity consultaba `session()` antes de que Accessibility se reconectara. En 3.1.2 la recuperación se arma en la primera apertura del store.

Por estas razones, **3.1.0 y 3.1.1 quedan superadas por 3.1.2** como candidata Core auditada.

## Pruebas automatizadas relevantes

La suite contiene regresiones para, entre otros casos:

- viaje real más largo que el tiempo estimado;
- pausa y reanudación sin sumar la pausa a `En servicio`;
- muerte del proceso dentro del mismo boot;
- reinicio del teléfono durante un viaje;
- primera apertura del store antes de Accessibility;
- regreso al mapa sin crear un servicio fantasma;
- pausa manual distinta de crash recovery;
- desconexión preservando minutos de servicio;
- cancelación sin contabilizar el viaje como ganado;
- siguiente viaje aceptado mientras el actual continúa;
- promoción del siguiente viaje al finalizar el actual;
- parser real de `$392 para ti`;
- filtros de importes `$/km`, `$/h`, saldo y billetera;
- regresiones previas de promociones, UI, licencias y estrategia 2.7.1.

GitHub Actions ejecuta obligatoriamente `testDebugUnitTest` y después `assembleDebug`. Un APK no se publica como artefacto si las pruebas o la compilación fallan.

## Archivos principales para revisar

Para una auditoría manual de 3.1 Core, revisar principalmente:

1. `app/src/main/java/com/cocotaxi/app/CocoStore.java`
2. `app/src/main/java/com/cocotaxi/app/CocotaxiAccessibilityService.java`
3. `app/src/main/java/com/cocotaxi/app/AssignmentSignals.java`
4. `app/src/main/java/com/cocotaxi/app/OfferParser.java`
5. `app/src/main/java/com/cocotaxi/app/TextCollector.java`
6. `app/src/test/java/com/cocotaxi/app/StoreTest.java`
7. `app/src/test/java/com/cocotaxi/app/FormulaTest.java`
8. `app/src/main/res/xml/accessibility_config.xml`
9. `app/build.gradle`

## Qué valida la auditoría y qué no

La auditoría de código, las pruebas unitarias y la compilación permiten verificar la coherencia de las transiciones y proteger contra regresiones reproducibles. No pueden demostrar por sí solas que cada versión futura de Cabify exponga exactamente los mismos textos/nodos Accessibility en todos los teléfonos.

Antes de congelar definitivamente Core para construir Data/Atlas encima, la validación de campo debe comprobar al menos:

- finalizar mediante botón/deslizamiento real de Cabify;
- cancelación real;
- cerrar/matar Coco en mitad de un viaje y volver a abrirlo;
- reiniciar el teléfono durante un viaje;
- recibir y aceptar un siguiente viaje mientras el actual sigue en servicio;
- aceptación manual cuando la tarjeta desaparece rápidamente;
- ofertas de pocos segundos y autoaceptación;
- que los relojes mostrados coincidan con tiempos reales durante una sesión larga.

## Estado

**3.1.2 Core es la candidata auditada de código para prueba de campo.**

No se debe iniciar 3.2 Atlas sobre una versión anterior de Core. Si las pruebas de campo anteriores no detectan una regresión, 3.1.2 puede congelarse como la base operativa para la siguiente etapa.
