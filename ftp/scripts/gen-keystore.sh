#!/usr/bin/env bash
# Tao keystore PKCS12 tu ky (dung cho thu nghiem). Production: dung chung chi that (vd Let's Encrypt) roi
# dong goi bang: openssl pkcs12 -export -in fullchain.pem -inkey privkey.pem -out ftps.p12
# Cach dung: FTP_TLS_KEYSTORE_PASSWORD=... ./gen-keystore.sh <hostname-hoac-IP> [duong-dan-dau-ra]
set -euo pipefail
HOST="${1:?usage: gen-keystore.sh <hostname-or-ip> [out.p12]}"
OUT="${2:-certs/ftps.p12}"
: "${FTP_TLS_KEYSTORE_PASSWORD:?set FTP_TLS_KEYSTORE_PASSWORD}"
mkdir -p "$(dirname "$OUT")"
if [[ "$HOST" =~ ^[0-9.]+$ ]]; then SAN="ip:$HOST"; else SAN="dns:$HOST"; fi
keytool -genkeypair -alias ftps -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 365 \
  -dname "CN=$HOST" -ext "san=$SAN" -storetype PKCS12 \
  -keystore "$OUT" -storepass "$FTP_TLS_KEYSTORE_PASSWORD"
echo "Da tao $OUT (tu ky, hieu luc 365 ngay)"
