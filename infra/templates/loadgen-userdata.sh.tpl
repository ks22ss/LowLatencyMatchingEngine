#!/bin/bash
set -euo pipefail
exec > /var/log/loadgen-setup.log 2>&1

echo "=== Installing Go ==="
dnf install -y git tar gzip
curl -fsSL https://go.dev/dl/go1.22.5.linux-amd64.tar.gz -o /tmp/go.tar.gz
tar -C /usr/local -xzf /tmp/go.tar.gz
export PATH=$${PATH}:/usr/local/go/bin
export GOPATH=/root/go
export HOME=/root
export XDG_CACHE_HOME=/root/.cache
export GOCACHE=/root/.cache/go-build
mkdir -p "$${GOCACHE}" "$${GOPATH}" "$${XDG_CACHE_HOME}"

echo "=== Cloning repo ==="
cd /opt
git clone -b ${repo_branch} ${repo_url} engine
cd engine/scripts/loadgen

echo "=== Building loadgen ==="
go build -o /usr/local/bin/loadgen .

echo "=== Waiting for engine at ${engine_ip}:9999 ==="
for i in $(seq 1 120); do
  if timeout 2 bash -c "echo > /dev/tcp/${engine_ip}/9999" 2>/dev/null; then
    echo "Engine reachable after $${i} attempts"
    break
  fi
  echo "Attempt $${i}: engine not ready, retrying in 5s..."
  sleep 5
done

echo "=== Starting loadgen ==="
/usr/local/bin/loadgen \
  -addr "${engine_ip}:9999" \
  -duration "${duration}" \
  -conns ${conns} \
  -rate ${rate} \
  -batch ${batch} \
  -cancel-pct ${cancel_pct} \
  -price 10000 \
  -price-jitter 500 \
  -qty-min 1 \
  -qty-max 10 \
  -seed $${RANDOM} \
  2>&1 | tee /var/log/loadgen-output.log

echo "=== Loadgen finished ==="
