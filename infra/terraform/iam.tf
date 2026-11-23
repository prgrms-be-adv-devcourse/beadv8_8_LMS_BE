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

# 배포 workflow가 SSM Run Command로 large에 명령을 보낸다. node에 SSM Agent가 붙으려면 이 권한이 있어야 한다.
# pod는 IMDS 차단(k8s/gateway/base/metadata-networkpolicy.yaml)으로 이 권한을 쓰지 못한다.
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.server.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# GitHub Actions OIDC provider는 계정에 URL당 하나만 둘 수 있다. 이미 있는 계정(devcos)은 만들지 않고 읽기만 한다.
resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]

  tags = var.tags
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 0 : 1

  url = "https://token.actions.githubusercontent.com"
}

locals {
  github_oidc_provider_arn = var.create_github_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github[0].arn
}

# 운영 배포(.github/workflows/deploy.yml)가 GitHub OIDC 토큰으로 받는 role이다. 액세스 키를 만들지 않는다(docs/deploy/k8s.md "운영 앱 배포").
data "aws_iam_policy_document" "github_deploy_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    # GitHub Environment prod의 job만 받는다. prod는 dev 브랜치만 허용한다.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = var.github_deploy_subjects
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name               = "${var.name_prefix}-github-deploy"
  description        = "GitHub Actions prod deploy(SSM Run Command)"
  assume_role_policy = data.aws_iam_policy_document.github_deploy_assume.json

  tags = var.tags
}

# large(server)에만 셸 명령을 보내고 결과를 읽는다. 명령은 large에서 root로 돈다(docs/deploy/security.md "알려진 위험").
data "aws_iam_policy_document" "ssm_deploy" {
  statement {
    sid       = "LargeOnly"
    actions   = ["ssm:SendCommand"]
    resources = ["arn:aws:ec2:${var.region}:${var.account_id}:instance/*"]

    condition {
      test     = "StringEquals"
      variable = "ssm:resourceTag/Name"
      values   = [aws_instance.node["server"].tags["Name"]]
    }
  }

  statement {
    sid       = "ShellDocument"
    actions   = ["ssm:SendCommand"]
    resources = ["arn:aws:ssm:${var.region}::document/AWS-RunShellScript"]
  }

  statement {
    sid       = "ReadResult"
    actions   = ["ssm:GetCommandInvocation"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "ssm_deploy" {
  name   = "ssm-deploy-large"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.ssm_deploy.json
}
