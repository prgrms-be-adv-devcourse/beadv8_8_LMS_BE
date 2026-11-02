terraform {
  # import 블록의 for_each는 1.7부터 쓸 수 있다.
  required_version = ">= 1.7"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}
