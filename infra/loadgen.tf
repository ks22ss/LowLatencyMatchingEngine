resource "aws_spot_instance_request" "loadgen" {
  count = var.loadgen_count

  ami                    = data.aws_ami.al2023.id
  instance_type          = var.loadgen_instance_type
  key_name               = var.key_name
  vpc_security_group_ids = [aws_security_group.loadgen.id]
  subnet_id              = data.aws_subnets.default.ids[0]

  associate_public_ip_address = true
  wait_for_fulfillment        = true
  spot_type                   = "one-time"

  root_block_device {
    volume_size = 10
    volume_type = "gp3"
  }

  user_data = templatefile("${path.module}/templates/loadgen-userdata.sh.tpl", {
    repo_url      = var.repo_url
    repo_branch   = var.repo_branch
    engine_ip     = aws_instance.engine.private_ip
    duration      = var.loadgen_duration
    conns         = var.loadgen_conns
    rate          = var.loadgen_rate
    cancel_pct    = var.loadgen_cancel_pct
    batch         = var.loadgen_batch
    workload_mode = var.loadgen_mode
    rest_spread   = var.loadgen_rest_spread
  })

  tags = { Name = "loadgen-${count.index}" }
}
