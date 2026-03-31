# Infrastructure (Terraform)

Provisions a chaos/load test environment on **AWS ap-southeast-1**:

| Role | Count | Instance type | Lifecycle |
|------|-------|---------------|-----------|
| Matching engine | 1 | `c5.xlarge` | on-demand |
| Load generators | 10 | `c5.large` | spot |
| Prometheus + Grafana | 1 | `t3.medium` | on-demand |

## Architecture

```
┌──────────────────────────────────────────── VPC (default) ────────────────────────────────────┐
│                                                                                               │
│  ┌─────────────┐     TCP :9999     ┌────────────────────┐                                     │
│  │  loadgen-0   │ ───────────────▶ │                    │                                     │
│  │  loadgen-1   │ ───────────────▶ │  matching-engine   │ :8081/metrics                       │
│  │  ...         │ ───────────────▶ │  (Netty + Disruptor│ ◀──── scrape ──── ┌──────────────┐  │
│  │  loadgen-9   │ ───────────────▶ │   + OrderBook)     │                   │  monitoring   │  │
│  └─────────────┘     (spot)        └────────────────────┘                   │  Prometheus   │  │
│                                                                             │  Grafana :3000│  │
│                                                                             └──────────────┘  │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Prerequisites

1. **AWS CLI** configured (`aws configure`) with credentials that can create EC2 + security groups.
2. **Terraform >= 1.5** installed.
3. An **EC2 key pair** in `ap-southeast-1` (for SSH access).
4. **Push your latest code** to the repo/branch so instances can `git clone` it.

## Quick start

```bash
cd infra

# 1. Create your tfvars (gitignored)
cp terraform.tfvars.example terraform.tfvars
#    Edit: set key_name and my_ip (curl ifconfig.me)

# 2. Init + apply
terraform init
terraform apply

# 3. Wait ~3 minutes for cloud-init to finish on all instances.

# 4. Open Grafana
#    URL is in terraform output: grafana_url
#    Login: admin / admin
#    Prometheus datasource is auto-provisioned.

# 5. Watch metrics
#    - matching.inbound.submit.total
#    - matching.trades.filled.total
#    - matching.submit.to.match.latency (histogram)
#    - matching.ring.publish.rejected.total

# 6. Tear down when done
terraform destroy
```

## SSH into instances

```bash
# Engine
ssh -i ~/.ssh/my-keypair.pem ec2-user@$(terraform output -raw engine_public_ip)

# Check engine logs
journalctl -u matching-engine -f

# Loadgen (check setup log)
ssh -i ~/.ssh/my-keypair.pem ec2-user@<loadgen-public-ip>
cat /var/log/loadgen-setup.log
cat /var/log/loadgen-output.log

# Monitoring
ssh -i ~/.ssh/my-keypair.pem ec2-user@$(terraform output -raw monitoring_public_ip)
cd /opt/monitoring && docker compose logs -f
```

## Customizing the load test

Edit `terraform.tfvars`:

| Variable | Default | Purpose |
|----------|---------|---------|
| `loadgen_count` | 10 | Number of spot load generators |
| `loadgen_duration` | 5m | How long each loadgen runs |
| `loadgen_rate` | 10000 | Target msg/s per loadgen instance |
| `loadgen_conns` | 4 | TCP connections per loadgen |
| `loadgen_cancel_pct` | 10 | Percent of messages that are CANCEL |

Total theoretical throughput = `loadgen_count * loadgen_rate` msg/s (e.g. 10 * 10000 = 100k msg/s).

## Cost estimate

- 1x `c5.xlarge` on-demand: ~$0.17/hr
- 10x `c5.large` spot: ~$0.03/hr each = ~$0.30/hr total
- 1x `t3.medium` on-demand: ~$0.04/hr
- **Total: ~$0.51/hr** (Singapore pricing, approximate)

A 30-minute test costs roughly **$0.25**. Don't forget `terraform destroy`.
