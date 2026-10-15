#!/usr/bin/env bash
# CI-only TLS backend. Certificate goes in the TEST APK; private key stays on the runner.
set -euo pipefail
solvit_clone_fixture="${RUNNER_TEMP:?}/solvit-clone-git-http"
mkdir -p "$solvit_clone_fixture" app/src/androidTest/assets
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost' \
  -keyout "$solvit_clone_fixture/key.pem" -out "$solvit_clone_fixture/cert.pem" 2>/dev/null
chmod 600 "$solvit_clone_fixture/key.pem"
cp "$solvit_clone_fixture/cert.pem" app/src/androidTest/assets/git-fixture.crt
nohup python3 scripts/git-http-fixture.py --root "$solvit_clone_fixture/repositories" \
  --cert "$solvit_clone_fixture/cert.pem" --key "$solvit_clone_fixture/key.pem" > "$solvit_clone_fixture/server.log" 2>&1 &
for solvit_attempt in $(seq 1 30); do
  if curl --fail --silent --cacert "$solvit_clone_fixture/cert.pem" https://localhost:19443/health; then exit 0; fi
  sleep 1
done
exit 1
