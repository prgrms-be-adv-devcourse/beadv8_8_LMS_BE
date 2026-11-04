# 사진·영상 파일. 업로드는 서버 경유이고, 조회는 서버가 발급한 Presigned GET이다(docs/deploy/s3.md).
# key는 환경 prefix(dev/·prod/)로 나눈다.

locals {
  # 계정·리전 단위 이름 공간(account-regional)을 쓰므로 이름이 다른 계정과 겹치지 않는다.
  bucket_name = "${var.name_prefix}-s3-${var.account_id}-${var.region}-an"
}

resource "aws_s3_bucket" "files" {
  bucket           = local.bucket_name
  bucket_namespace = "account-regional"

  tags = var.tags
}

resource "aws_s3_bucket_public_access_block" "files" {
  bucket = aws_s3_bucket.files.id

  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

# ACL을 쓰지 않는다. 모든 객체는 버킷 소유자 것이다.
resource "aws_s3_bucket_ownership_controls" "files" {
  bucket = aws_s3_bucket.files.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "files" {
  bucket = aws_s3_bucket.files.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled       = true
    blocked_encryption_types = ["SSE-C"]
  }
}

resource "aws_s3_bucket_cors_configuration" "files" {
  bucket = aws_s3_bucket.files.id

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = var.cors_allowed_methods
    allowed_origins = var.cors_allowed_origins
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}
