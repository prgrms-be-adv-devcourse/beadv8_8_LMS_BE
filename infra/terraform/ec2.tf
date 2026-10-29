# k3s cluster의 node 두 대(docs/deploy/aws-k3s.md). server는 t3.large(control plane, core-data), agent는 t3.medium(app-observe)이다.
# 이름 접미어는 devcos에서 콘솔로 만든 이름(team08-hapbang-ec2, team08-hapbang-ec2-2)을 따른다.

locals {
  nodes = {
    server = { instance_type = var.instance_types.server, name_suffix = "" }
    agent  = { instance_type = var.instance_types.agent, name_suffix = "-2" }
  }

  # 노드마다 기본 네트워크 인터페이스에 붙일 태그. 키는 "<node>/<태그 키>"다.
  network_interface_tags = merge([
    for node, cfg in local.nodes : {
      for key, value in merge(var.tags, { Name = "${var.name_prefix}-ec2${cfg.name_suffix}" }) :
      "${node}/${key}" => { node = node, key = key, value = value }
    }
  ]...)
}

data "aws_ami" "ubuntu" {
  count = var.ami_id == null ? 1 : 0

  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }
}

# 두 node가 같은 키 페어를 쓴다.
resource "aws_key_pair" "this" {
  key_name   = "${var.name_prefix}-key"
  public_key = var.public_key

  tags = var.tags

  # AWS가 공개 키를 돌려주지 않아 가져온 키 페어와 비교할 수 없다. 키를 바꿀 때는 새 이름으로 만든다.
  lifecycle {
    ignore_changes = [public_key]
  }
}

resource "aws_instance" "node" {
  for_each = local.nodes

  ami                    = var.ami_id != null ? var.ami_id : data.aws_ami.ubuntu[0].id
  instance_type          = each.value.instance_type
  subnet_id              = aws_subnet.public.id
  vpc_security_group_ids = [aws_security_group.server.id]
  key_name               = aws_key_pair.this.key_name
  # 앱이 어느 node에서 돌든 S3에 접근하도록 두 node에 같은 역할을 붙인다.
  iam_instance_profile = aws_iam_instance_profile.server.name

  # docs/deploy/server-setup.md의 사전 준비와 1단계. Compose 운영은 server에서만 한다.
  # 이후 단계(설정 파일, 비밀값, 인증서, 배포 키)와 k3s 설치(docs/deploy/aws-k3s.md)는 사람이 한다.
  user_data = each.key == "server" ? file("${path.module}/user_data.sh") : null

  # 크레딧을 다 쓰면 기준 성능으로 떨어진다. unlimited의 추가 요금을 피한다.
  credit_specification {
    cpu_credits = "standard"
  }

  # IMDSv2만 허용한다. 컨테이너(Docker bridge) 안의 앱도 인스턴스 역할 자격 증명을 받도록 hop limit은 2다.
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
    http_protocol_ipv6          = "disabled"
    instance_metadata_tags      = "disabled"
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_size
    iops                  = 3000
    throughput            = 125
    encrypted             = var.root_volume_encrypted
    delete_on_termination = true

    tags = merge(var.tags, { Name = "${var.name_prefix}-ec2${each.value.name_suffix}" })
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-ec2${each.value.name_suffix}" })

  # AMI와 user_data는 처음 만들 때만 쓴다. 새 AMI가 나오거나 스크립트를 고쳐도 서버를 다시 만들지 않는다.
  lifecycle {
    ignore_changes = [ami, user_data]
  }
}

# aws_instance는 기본 네트워크 인터페이스에 태그를 붙이지 않는다.
resource "aws_ec2_tag" "network_interface" {
  for_each = local.network_interface_tags

  resource_id = aws_instance.node[each.value.node].primary_network_interface_id
  key         = each.value.key
  value       = each.value.value
}

# 인스턴스를 다시 만들어도 IP가 바뀌지 않도록 Elastic IP를 쓴다. DuckDNS는 server의 IP를 가리킨다.
resource "aws_eip" "node" {
  for_each = local.nodes

  domain = "vpc"

  tags = merge(var.tags, { Name = "${var.name_prefix}-eip${each.value.name_suffix}" })
}

resource "aws_eip_association" "node" {
  for_each = local.nodes

  instance_id   = aws_instance.node[each.key].id
  allocation_id = aws_eip.node[each.key].id
}
