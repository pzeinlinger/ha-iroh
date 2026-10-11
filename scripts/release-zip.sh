#!/bin/sh
# Build static musl bridge binaries and dist/ha_iroh.zip (HACS zip_release layout).
# ZIP=0 skips the zip; ARCHES= (empty) skips the build.
# ARCHES="aarch64" ./scripts/release-zip.sh   # local dev: only one arch
set -eu
cd "$(dirname "$0")/.."
bin=custom_components/ha_iroh/bin
mkdir -p "$bin" dist target/cargo-registry
for arch in ${ARCHES-x86_64 aarch64}; do
  case $arch in x86_64) plat=linux/amd64 ;; aarch64) plat=linux/arm64 ;; *) echo "bad arch $arch" >&2; exit 1 ;; esac
  docker run --rm --platform $plat -v "$PWD":/src -v "$PWD/target/cargo-registry":/usr/local/cargo/registry -w /src rust:alpine sh -c \
    "apk add -q musl-dev && cargo build --release -p ha_iroh_bridge --target-dir /src/target/alpine-$arch"
  cp "target/alpine-$arch/release/ha-iroh-bridge" "$bin/ha-iroh-bridge-$arch"
done
[ "${ZIP:-1}" = 1 ] || exit 0
rm -f dist/ha_iroh.zip
(cd custom_components/ha_iroh && zip -qr ../../dist/ha_iroh.zip . -x '*__pycache__*')
