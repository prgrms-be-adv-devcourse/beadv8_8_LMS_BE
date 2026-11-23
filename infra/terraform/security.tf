# 80·443은 large의 ServiceLB → Envoy Gateway(HTTP→HTTPS 리다이렉트, cert-manager HTTP-01, HTTPS)가 받는다.
# 22는 사람(ubuntu, 키 페어)이 쓴다. 기존 Compose 배포(deploy, command= 제한 키)용으로 열었다. 비밀번호 로그인은 꺼져 있다.
# 6443·10250·8472는 k3s node 사이 통신이다. 6443은 kubectl을 쓰는 사람의 IP /32에도 연다(docs/deploy/aws-k3s.md).

locals {
  ingress_rules = merge(
    { for cidr in var.ssh_ingress_cidrs : "ssh-${cidr}" => { port = 22, cidr = cidr } },
    {
      http  = { port = 80, cidr = "0.0.0.0/0" }
      https = { port = 443, cidr = "0.0.0.0/0" }
    },
  )

  # 두 node가 같은 보안 그룹을 쓰므로 source를 자기 자신으로 둔다. 키는 규칙 설명이 된다.
  node_ingress_rules = {
    k3s-api           = { protocol = "tcp", port = 6443 }  # agent → server API
    k3s-kubelet       = { protocol = "tcp", port = 10250 } # kubelet·metrics
    k3s-flannel-vxlan = { protocol = "udp", port = 8472 }  # Flannel VXLAN(pod 네트워크)
  }
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

resource "aws_vpc_security_group_ingress_rule" "node" {
  for_each = local.node_ingress_rules

  security_group_id            = aws_security_group.server.id
  referenced_security_group_id = aws_security_group.server.id
  ip_protocol                  = each.value.protocol
  from_port                    = each.value.port
  to_port                      = each.value.port
  description                  = each.key
}

# 로컬 kubectl이 large EIP의 API 서버에 붙을 때 쓴다(docs/deploy/aws-k3s.md "로컬 kubectl 접근").
resource "aws_vpc_security_group_ingress_rule" "kubectl" {
  for_each = var.kubectl_ingress_cidrs

  security_group_id = aws_security_group.server.id
  ip_protocol       = "tcp"
  from_port         = 6443
  to_port           = 6443
  cidr_ipv4         = each.value
  description       = "k3s API - ${each.key}"
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.server.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}
