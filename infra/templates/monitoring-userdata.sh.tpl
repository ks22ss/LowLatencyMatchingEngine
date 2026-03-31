#!/bin/bash
set -euo pipefail
exec > /var/log/monitoring-setup.log 2>&1

echo "=== Installing Docker ==="
dnf install -y docker
systemctl enable --now docker

echo "=== Installing Docker Compose plugin ==="
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

mkdir -p /opt/monitoring

# ── Prometheus config ─────────────────────────────────────────────────────────
cat > /opt/monitoring/prometheus.yml <<'PROMEOF'
global:
  scrape_interval: 5s

scrape_configs:
  - job_name: matching-engine
    static_configs:
      - targets: ['${engine_ip}:8081']
        labels:
          instance: engine
PROMEOF

# ── Grafana datasource provisioning ──────────────────────────────────────────
mkdir -p /opt/monitoring/grafana/provisioning/datasources
cat > /opt/monitoring/grafana/provisioning/datasources/prometheus.yml <<'DSEOF'
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
DSEOF

# ── Docker Compose file ──────────────────────────────────────────────────────
cat > /opt/monitoring/docker-compose.yml <<'DCEOF'
services:
  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus-data:/prometheus
    ports:
      - "9090:9090"
    restart: unless-stopped

  grafana:
    image: grafana/grafana:latest
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning
      - grafana-data:/var/lib/grafana
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
      - GF_AUTH_ANONYMOUS_ENABLED=true
      - GF_AUTH_ANONYMOUS_ORG_ROLE=Viewer
    ports:
      - "3000:3000"
    depends_on:
      - prometheus
    restart: unless-stopped

volumes:
  prometheus-data:
  grafana-data:
DCEOF

echo "=== Starting Prometheus + Grafana ==="
cd /opt/monitoring
docker compose up -d

echo "=== Monitoring stack started ==="
