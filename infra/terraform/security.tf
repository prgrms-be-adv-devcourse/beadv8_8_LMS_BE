# 80·443은 edge nginx(HTTP→HTTPS 리다이렉트, certbot 인증, HTTPS)가 받는다.
# 22는 사람(ubuntu, 키 페어)과 GitHub Actions 배포(deploy, command= 제한 키)가 쓴다. 비밀번호 로그인은 꺼져 있다.

locals {
  ingress_rules = merge(
    { for cidr in var.ssh_ingress_cidrs : "ssh-${cidr}" => { port = 22, cidr = cidr } },
    {
      http  = { port = 80, cidr = "0.0.0.0/0" }
      https = { port = 443, cidr = "0.0.0.0/0" }
    },
  )
}

resource "aws_security_group" "server" {
  name        = "${var.name_prefix}-sg"
  description = "enable SSH http https"
  vpc_id      = aws_vpc.this.id

  tags = merge(var.tags, { Name = "${var.name_prefix}-sg" })
}

resource "aws_vpc_security_group_ingress_rule" "this" {
  for_each = local.ingress_rules

  security_group_id = aws_security_group.server.id
  ip_protocol       = "tcp"
  from_port         = each.value.port
  to_port           = each.value.port
  cidr_ipv4         = each.value.cidr
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.server.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}
