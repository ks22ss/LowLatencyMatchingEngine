#!/bin/bash
set -euo pipefail
exec > /var/log/engine-setup.log 2>&1

echo "=== Installing JDK 21 (Corretto) ==="
# Install the full JDK (javac) so Gradle toolchains can compile.
dnf install -y java-21-amazon-corretto-devel git

echo "=== Cloning repo ==="
cd /opt
git clone -b ${repo_branch} ${repo_url} engine
cd engine

echo "=== Building distribution ==="
export JAVA_HOME="/usr/lib/jvm/java-21-amazon-corretto"
export PATH="$JAVA_HOME/bin:$PATH"
chmod +x gradlew
./gradlew installDist

echo "=== Creating systemd service ==="
cat > /etc/systemd/system/matching-engine.service <<'UNIT'
[Unit]
Description=Low Latency Matching Engine
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/engine
ExecStart=/opt/engine/build/install/LowLatencyMatchingEngine/bin/LowLatencyMatchingEngine 9999
Environment=METRICS_PORT=8081
Restart=on-failure
RestartSec=3
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now matching-engine

echo "=== Engine started ==="
