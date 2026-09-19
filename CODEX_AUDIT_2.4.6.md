# COCOTAXI 2.4.6 — entrada para Codex

Esta rama contiene la cadena reproducible que genera el proyecto Android exacto de COCOTAXI 2.4.6.

## Preparar el proyecto completo

Ejecuta:

```bash
bash prepare_project.sh
cd COCOTAXI_2.4.6
```

Después de ese comando, Codex puede auditar directamente todos los archivos Java, recursos, pruebas y Gradle del proyecto.

## Alcance de 2.4.6

- No cambiar la lógica de decisión de Cabify, promociones ni sesión.
- Adaptación visual al diseño de referencia: fondo azul marino, tarjetas, botones compactos y acentos verde/amarillo/azul/violeta.
- PIN local opcional de inicio en Ajustes > Seguridad.
- El PIN usa PBKDF2-HMAC-SHA256 + sal aleatoria y bloqueo temporal tras cinco errores.
- versionName 2.4.6 / versionCode 21.

## Auditoría recomendada

Revisar primero:
- `app/src/main/java/com/cocotaxi/app/MainActivity.java`
- `app/src/main/java/com/cocotaxi/app/PinStore.java`
- `app/src/main/java/com/cocotaxi/app/DecisionEngine.java`
- `app/src/main/java/com/cocotaxi/app/CocoStore.java`
- `app/src/main/java/com/cocotaxi/app/CocotaxiAccessibilityService.java`
- `app/src/main/java/com/cocotaxi/app/CocotaxiOverlayService.java`

La prioridad es encontrar errores funcionales sin alterar fórmulas ni criterios de decisión salvo que se demuestre un bug.
