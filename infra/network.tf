# ── Engine security group ─────────────────────────────────────────────────────

resource "aws_security_group" "engine" {
  name_prefix = "engine-"
  description = "Matching engine: TCP ingress + metrics scrape"
  vpc_id      = data.aws_vpc.default.id

  # SSH from operator
  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.my_ip]
  }

  # TCP ingress from load generators
  ingress {
    description     = "Engine TCP ingress from loadgens"
    from_port       = 9999
    to_port         = 9999
    protocol        = "tcp"
    security_groups = [aws_security_group.loadgen.id]
  }

  # Prometheus metrics scrape from monitoring + operator
  ingress {
    description     = "Metrics from monitoring"
    from_port       = 8081
    to_port         = 8081
    protocol        = "tcp"
    security_groups = [aws_security_group.monitoring.id]
  }
  ingress {
    description = "Metrics from operator"
    from_port   = 8081
    to_port     = 8081
    protocol    = "tcp"
    cidr_blocks = [var.my_ip]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "engine" }
}

# ── Load generator security group ────────────────────────────────────────────

resource "aws_security_group" "loadgen" {
  name_prefix = "loadgen-"
  description = "Load generator spots"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.my_ip]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "loadgen" }
}

# ── Monitoring security group ─────────────────────────────────────────────────

resource "aws_security_group" "monitoring" {
  name_prefix = "monitoring-"
  description = "Prometheus + Grafana"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.my_ip]
  }

  ingress {
    description = "Grafana"
    from_port   = 3000
    to_port     = 3000
    protocol    = "tcp"
    cidr_blocks = [var.my_ip]
  }

  ingress {
    description = "Prometheus UI"
    from_port   = 9090
    to_port     = 9090
    protocol    = "tcp"
    cidr_blocks = [var.my_ip]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "monitoring" }
}
