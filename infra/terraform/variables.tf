# 기본값은 새 계정에 처음 만들 때의 값이다. devcos 계정의 현재 값은 devcos.tfvars.example을 따른다.

variable "account_id" {
  description = "적용할 AWS 계정 ID. 버킷 이름과 계정 확인에 쓴다"
  type        = string
}

variable "region" {
  description = "리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "availability_zone" {
  description = "퍼블릭 서브넷과 EC2를 둘 가용 영역. null이면 region의 a 영역(<region>a)"
  type        = string
  default     = null
}

variable "name_prefix" {
  description = "리소스 이름 접두어 (devcos: team08-hapbang)"
  type        = string
  default     = "hapbang"
}

variable "tags" {
  description = "모든 리소스에 붙이는 태그 (devcos: Team=devcos-team08)"
  type        = map(string)
  default     = { Project = "hapbang" }
}

variable "vpc_cidr" {
  description = "VPC CIDR"
  type        = string
  default     = "10.0.0.0/16"
}

variable "public_subnet_cidr" {
  description = "퍼블릭 서브넷 CIDR"
  type        = string
  default     = "10.0.0.0/20"
}

variable "instance_type" {
  description = "EC2 인스턴스 유형"
  type        = string
  default     = "t3.large"
}

variable "ami_id" {
  description = "EC2 AMI. null이면 Ubuntu 24.04 공식 최신 AMI를 쓴다. 만든 뒤에는 바꿔도 반영하지 않는다"
  type        = string
  default     = null
}

variable "root_volume_size" {
  description = "루트 볼륨 크기(GB)"
  type        = number
  default     = 50
}

variable "root_volume_encrypted" {
  description = "루트 볼륨 암호화. 바꾸면 EC2를 새로 만든다 (devcos: false)"
  type        = bool
  default     = true
}

variable "public_key" {
  description = "EC2 키 페어 공개 키 (ssh-keygen -y -f <키>.pem). 만든 뒤에는 바꿔도 반영하지 않는다"
  type        = string
}

variable "ssh_ingress_cidrs" {
  description = "SSH(22) 허용 CIDR. GitHub 호스티드 러너가 SSH로 배포하므로 IP를 고정할 수 없다"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "cors_allowed_origins" {
  description = "S3 CORS 허용 origin (prod·dev 프론트 도메인, 로컬 개발 서버)"
  type        = list(string)
}

variable "cors_allowed_methods" {
  description = "S3 CORS 허용 메서드. 업로드는 서버 경유라 브라우저는 Presigned GET만 쓴다 (devcos: PUT 포함)"
  type        = list(string)
  default     = ["GET", "HEAD"]
}

variable "s3_env_prefixes" {
  description = "서버가 읽고 쓸 S3 key prefix (환경 이름)"
  type        = list(string)
  default     = ["dev", "prod"]
}

variable "iam_role_description" {
  description = "EC2 인스턴스 역할 설명"
  type        = string
  default     = "Hapbang server role - reads and writes application files in S3"
}

variable "import_ids" {
  description = "이미 있는 리소스를 가져올 때의 ID (imports.tf). 새로 만들 때는 null"
  type = object({
    vpc_id                          = string
    subnet_id                       = string
    internet_gateway_id             = string
    route_table_id                  = string
    security_group_id               = string
    security_group_ingress_rule_ids = map(string)
    security_group_egress_rule_id   = string
    instance_id                     = string
    network_interface_id            = string
    eip_allocation_id               = string
    eip_association_id              = string
  })
  default = null
}
