output "public_ips" {
  description = "node별 Elastic IP. DuckDNS의 prod·dev 도메인은 server의 주소를 가리켜야 한다"
  value       = { for node, eip in aws_eip.node : node => eip.public_ip }
}

output "instance_ids" {
  description = "node별 EC2 인스턴스 ID"
  value       = { for node, instance in aws_instance.node : node => instance.id }
}

output "bucket_name" {
  description = "S3 버킷 이름. 서버 env 파일의 S3_BUCKET에 넣는다"
  value       = aws_s3_bucket.files.bucket
}
