# devcos 계정에 콘솔로 만든 리소스를 state로 가져온다. var.import_ids가 null이면(새 계정) 아무것도 가져오지 않는다.
# 이름으로 찾는 리소스(키 페어, S3, IAM)는 ID 대신 이름을 쓴다.

locals {
  import = var.import_ids == null ? {} : { existing = var.import_ids }
}

import {
  for_each = local.import
  to       = aws_vpc.this
  id       = each.value.vpc_id
}

import {
  for_each = local.import
  to       = aws_internet_gateway.this
  id       = each.value.internet_gateway_id
}

import {
  for_each = local.import
  to       = aws_subnet.public
  id       = each.value.subnet_id
}

import {
  for_each = local.import
  to       = aws_route_table.public
  id       = each.value.route_table_id
}

import {
  for_each = local.import
  to       = aws_route_table_association.public
  id       = "${each.value.subnet_id}/${each.value.route_table_id}"
}

import {
  for_each = local.import
  to       = aws_security_group.server
  id       = each.value.security_group_id
}

import {
  for_each = var.import_ids == null ? {} : var.import_ids.security_group_ingress_rule_ids
  to       = aws_vpc_security_group_ingress_rule.this[each.key]
  id       = each.value
}

import {
  for_each = local.import
  to       = aws_vpc_security_group_egress_rule.all
  id       = each.value.security_group_egress_rule_id
}

import {
  for_each = local.import
  to       = aws_key_pair.this
  id       = "${var.name_prefix}-key"
}

import {
  for_each = local.import
  to       = aws_instance.server
  id       = each.value.instance_id
}

import {
  for_each = var.import_ids == null ? {} : merge(var.tags, { Name = "${var.name_prefix}-ec2" })
  to       = aws_ec2_tag.network_interface[each.key]
  id       = "${var.import_ids.network_interface_id},${each.key}"
}

import {
  for_each = local.import
  to       = aws_eip.server
  id       = each.value.eip_allocation_id
}

import {
  for_each = local.import
  to       = aws_eip_association.server
  id       = each.value.eip_association_id
}

import {
  for_each = local.import
  to       = aws_s3_bucket.files
  id       = local.bucket_name
}

import {
  for_each = local.import
  to       = aws_s3_bucket_public_access_block.files
  id       = local.bucket_name
}

import {
  for_each = local.import
  to       = aws_s3_bucket_ownership_controls.files
  id       = local.bucket_name
}

import {
  for_each = local.import
  to       = aws_s3_bucket_server_side_encryption_configuration.files
  id       = local.bucket_name
}

import {
  for_each = local.import
  to       = aws_s3_bucket_cors_configuration.files
  id       = local.bucket_name
}

import {
  for_each = local.import
  to       = aws_iam_role.server
  id       = "${var.name_prefix}-ec2-role"
}

import {
  for_each = local.import
  to       = aws_iam_role_policy.s3_access
  id       = "${var.name_prefix}-ec2-role:${var.name_prefix}-s3-access"
}

import {
  for_each = local.import
  to       = aws_iam_instance_profile.server
  id       = "${var.name_prefix}-ec2-role"
}
