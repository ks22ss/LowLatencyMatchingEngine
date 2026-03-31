variable "region" {
  description = "AWS region"
  default     = "ap-southeast-1"
}

variable "key_name" {
  description = "EC2 key pair name for SSH access"
  type        = string
}

variable "my_ip" {
  description = "Your public IP in CIDR notation for SSH + Grafana access (e.g. 1.2.3.4/32)"
  type        = string
}

variable "repo_url" {
  description = "Git repo URL to clone on instances"
  default     = "https://github.com/ks22ss/LowLatencyMatchingEngine.git"
}

variable "repo_branch" {
  description = "Git branch to checkout"
  default     = "main"
}

# ── Instance types ────────────────────────────────────────────────────────────

variable "engine_instance_type" {
  description = "Instance type for the matching engine server"
  default     = "c5.xlarge"
}

variable "loadgen_instance_type" {
  description = "Spot instance type for each load generator"
  default     = "c5.large"
}

variable "monitoring_instance_type" {
  description = "Instance type for Prometheus + Grafana"
  default     = "t3.medium"
}

# ── Load generator tunables ───────────────────────────────────────────────────

variable "loadgen_count" {
  description = "Number of spot load-generator instances"
  default     = 10
}

variable "loadgen_duration" {
  description = "How long each loadgen runs (Go duration string, e.g. 5m)"
  default     = "5m"
}

variable "loadgen_rate" {
  description = "Target total msg/s per loadgen instance (0 = as fast as possible)"
  default     = 10000
}

variable "loadgen_conns" {
  description = "TCP connections per loadgen instance"
  default     = 4
}

variable "loadgen_cancel_pct" {
  description = "Percent of messages that are CANCEL (0..100)"
  default     = 10
}

variable "loadgen_batch" {
  description = "Frames per TCP write (batching reduces syscalls; typical 64–256)"
  default     = 128
}

variable "loadgen_mode" {
  description = "Go loadgen -mode: crossing | rest-heavy | cancel-heavy | market-heavy; empty string uses loadgen_cancel_pct only (no -mode flag)"
  type        = string
  default     = ""
}

variable "loadgen_rest_spread" {
  description = "Optional -rest-spread for rest-heavy (half-spread each side of loadgen_price); 0 = omit (Go defaults 2000 when mode is rest-heavy)"
  type        = number
  default     = 0
}
