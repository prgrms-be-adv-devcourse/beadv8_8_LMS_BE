# 서버는 액세스 키 없이 EC2 인스턴스 역할로 S3에 접근한다.

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "server" {
  name               = "${var.name_prefix}-ec2-role"
  description        = var.iam_role_description
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json

  tags = var.tags
}

# 환경 prefix 아래에서 올리기·읽기만 한다. 앱이 객체를 지우지 않으므로 DeleteObject는 주지 않는다.
data "aws_iam_policy_document" "s3_access" {
  statement {
    sid       = "ObjectReadWrite"
    actions   = ["s3:PutObject", "s3:GetObject"]
    resources = [for prefix in var.s3_env_prefixes : "${aws_s3_bucket.files.arn}/${prefix}/*"]
  }

  # ListBucket이 없으면 없는 객체의 HEAD가 404 대신 403으로 온다.
  statement {
    sid       = "ListForHead404"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.files.arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = [for prefix in var.s3_env_prefixes : "${prefix}/*"]
    }
  }
}

resource "aws_iam_role_policy" "s3_access" {
  name   = "${var.name_prefix}-s3-access"
  role   = aws_iam_role.server.id
  policy = data.aws_iam_policy_document.s3_access.json
}

resource "aws_iam_instance_profile" "server" {
  name = aws_iam_role.server.name
  role = aws_iam_role.server.name
}
