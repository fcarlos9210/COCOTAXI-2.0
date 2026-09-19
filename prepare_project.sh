#!/usr/bin/env bash
set -euo pipefail

# Reconstruct the exact COCOTAXI 2.4.6 Android project from the verified source chain.
ROOT="$(cd "$(dirname "$0")" && pwd)"
WORK="$ROOT/.cocotaxi-build-src"
OUT="$ROOT/COCOTAXI_2.4.6"

rm -rf "$WORK" "$OUT"
mkdir -p "$WORK"
unzip -q "$ROOT/COCOTAXI_2.4.2_sesion_icono_sin_espera_fuentes.zip" -d "$WORK"
PROJECT_DIR="$(find "$WORK" -name gradlew -type f -printf '%h\n' | head -n1)"
if [ -z "$PROJECT_DIR" ]; then
  echo "No se encontro el proyecto Android base" >&2
  exit 1
fi

base64 -d "$ROOT/cocotaxi-2.4.3.patch.gz.b64" | gzip -dc > "$WORK/2.4.3.patch"
patch --batch --forward -p1 -d "$PROJECT_DIR" < "$WORK/2.4.3.patch"

base64 -d "$ROOT/cocotaxi-2.4.4-app.patch.gz.b64" | gzip -dc > "$WORK/2.4.4.patch"
patch --batch --forward -p1 -d "$PROJECT_DIR" < "$WORK/2.4.4.patch"

cat "$ROOT"/cocotaxi-2.4.5-app.patch.gz.b64.part{1,2,3,4} \
  | base64 -d | gzip -dc > "$WORK/2.4.5.patch"
patch --batch --forward -p1 -d "$PROJECT_DIR" < "$WORK/2.4.5.patch"

cat "$ROOT"/cocotaxi-2.4.6-ui-pin.patch.gz.b64.part{1,2,3,4,5} \
  | base64 -d | gzip -dc > "$WORK/2.4.6.patch"
patch --batch --forward -p5 -d "$PROJECT_DIR" < "$WORK/2.4.6.patch"

mv "$PROJECT_DIR" "$OUT"
rm -rf "$WORK"

echo "COCOTAXI 2.4.6 listo en: $OUT"
echo "Para auditar/compilar: cd COCOTAXI_2.4.6"
