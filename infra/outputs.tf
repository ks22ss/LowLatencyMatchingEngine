output "engine_public_ip" {
  description = "Public IP of the matching engine instance"
  value       = aws_instance.engine.public_ip
}

output "engine_private_ip" {
  description = "Private IP of the matching engine (used by loadgens + Prometheus)"
  value       = aws_instance.engine.private_ip
}

output "monitoring_public_ip" {
  description = "Public IP of the Prometheus + Grafana instance"
  value       = aws_instance.monitoring.public_ip
}

output "grafana_url" {
  description = "Grafana dashboard URL (admin/admin)"
  value       = "http://${aws_instance.monitoring.public_ip}:3000"
}

output "prometheus_url" {
  description = "Prometheus UI URL"
  value       = "http://${aws_instance.monitoring.public_ip}:9090"
}

output "engine_metrics_url" {
  description = "Engine Prometheus metrics endpoint (direct)"
  value       = "http://${aws_instance.engine.public_ip}:8081/metrics"
}

output "loadgen_spot_request_ids" {
  description = "Spot instance request IDs for load generators"
  value       = aws_spot_instance_request.loadgen[*].id
}
