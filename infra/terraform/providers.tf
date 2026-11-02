provider "aws" {
  region = var.region

  # 프로필을 잘못 골라 다른 계정(devcos ↔ 개인)에 적용하는 것을 막는다.
  allowed_account_ids = [var.account_id]
}
