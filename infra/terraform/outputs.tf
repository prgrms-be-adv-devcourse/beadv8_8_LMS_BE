output "public_ip" {
  description = "서버 Elastic IP. DuckDNS의 prod·dev 도메인이 이 주소를 가리켜야 한다"
  value       = aws_eip.server.public_ip
}

output "instance_id" {
  description = "EC2 인스턴스 ID"
  value       = aws_instance.server.id
}

output "bucket_name" {
  description = "S3 버킷 이름. 서버 env 파일의 S3_BUCKET에 넣는다"
  value       = aws_s3_bucket.files.bucket
}
