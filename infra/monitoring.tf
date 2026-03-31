resource "aws_instance" "monitoring" {
  ami                    = data.aws_ami.al2023.id
  instance_type          = var.monitoring_instance_type
  key_name               = var.key_name
  vpc_security_group_ids = [aws_security_group.monitoring.id]
  subnet_id              = data.aws_subnets.default.ids[0]

  associate_public_ip_address = true

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
  }

  user_data = templatefile("${path.module}/templates/monitoring-userdata.sh.tpl", {
    engine_ip = aws_instance.engine.private_ip
  })

  tags = { Name = "monitoring" }
}
