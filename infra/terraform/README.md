# AWS 인프라 (Terraform)

합방 서버가 쓰는 AWS 리소스를 코드로 둔다. 지금 devcos 계정에 콘솔로 만들어 쓰고 있는 구성을 그대로 옮긴 것이고, 프로젝트가 끝난 뒤 다른 계정에 같은 구성을 다시 만들 때 쓴다(#337).

## 관리하는 것

| 파일 | 리소스 |
| --- | --- |
| `network.tf` | VPC, 퍼블릭 서브넷 1개, Internet Gateway, route table과 서브넷 연결 |
| `security.tf` | 보안 그룹: 인바운드 22·80·443, 아웃바운드 전체 |
| `ec2.tf` | k3s node EC2 2대(server t3.large, agent t3.medium. 둘 다 gp3 50GB, IMDSv2 필수, CPU 크레딧 standard), 공용 키 페어, node별 Elastic IP와 네트워크 인터페이스 태그 |
| `s3.tf` | 파일 버킷 1개: 퍼블릭 액세스 차단, ACL 비활성, SSE-S3, CORS |
| `iam.tf` | EC2 인스턴스 역할, instance profile, S3 인라인 정책(`dev/*`·`prod/*`의 Put·Get, 같은 prefix의 List) |
| `user_data.sh` | server EC2를 처음 만들 때 실행: 시간대, Docker, `deploy` 계정, `/opt/hapbang`, `hapbang-edge` 네트워크. 기존 Compose 운영용 준비이고 Compose는 2026-10-30에 지웠다(#440). 지금 EC2와 plan 차이가 생기지 않게 내용은 그대로 둔다 |
| `imports.tf` | 이미 있는 리소스를 가져오는 import 블록(`import_ids`가 있을 때만) |

## 관리하지 않는 것

- **EC2 안의 구성**: k3s 설치 → [aws-k3s.md](../../docs/deploy/aws-k3s.md#k3s-설치). cluster 안의 resource(PostgreSQL, Envoy Gateway, cert-manager 인증서, SealedSecret) → [k8s.md](../../docs/deploy/k8s.md)
- **GitHub 배포용 IAM role(OIDC)과 인스턴스 역할의 `AmazonSSMManagedInstanceCore`**: 콘솔에서 만들었다(#374, [k8s.md](../../docs/deploy/k8s.md#운영-앱-배포))
- **DNS**: DuckDNS(AWS 밖). Elastic IP가 바뀌면 직접 갱신한다.
- **쓰지 않는 서비스**: ECR(이미지는 GHCR), ACM·ELB(TLS는 nginx), CloudWatch Logs, Route 53
- **devcos 계정 공용 설정**: 09:00~18:00 EC2 자동 시작·중지(운영진 관리), VPC를 만들 때 함께 생긴 기본 route table·NACL·보안 그룹
- **원격 state·CI**: state는 로컬에 둔다. plan·apply는 로컬에서만 하고, CI에서는 검사하지 않는다.

## 기본값과 devcos 값

기본값은 새 계정에 처음 만들 때의 값이다. devcos 계정의 현재 값은 `devcos.tfvars.example`에 있다.

| 변수 | 기본값 | devcos | 이유 |
| --- | --- | --- | --- |
| `name_prefix` | `hapbang` | `team08-hapbang` | devcos는 팀 번호를 붙이는 계정 규칙이 있다 |
| `tags` | `Project=hapbang` | `Team=devcos-team08` | devcos 계정 규칙 |
| `root_volume_encrypted` | `true` | `false` | 바꾸면 EC2를 새로 만들기 때문에 devcos는 그대로 둔다 |
| `cors_allowed_methods` | `GET`, `HEAD` | `PUT`, `GET`, `HEAD` | 업로드가 서버 경유로 바뀌어(#106) 브라우저는 S3에 PUT하지 않는다 |
| `ssh_ingress_cidrs` | `0.0.0.0/0` | `0.0.0.0/0` | GitHub 호스티드 러너가 SSH로 배포하므로 IP를 고정할 수 없다. 키 인증만 허용하고 배포 키는 `command=`로 제한한다 |

S3 버킷은 계정·리전 단위 이름 공간(account-regional)을 쓴다. 이름은 `<name_prefix>-s3-<계정 ID>-<리전>-an`으로 정해진다.

## 공통 주의

- `*.tfstate`, `*.tfvars`(`.example` 제외)는 gitignore한다. 계정 ID, 리소스 ID, IP가 들어 있으니 공개 레포에 올리지 않는다.
- 계정마다 workspace를 나눈다(state 파일이 `terraform.tfstate.d/<workspace>/`에 따로 생긴다).
- provider가 `account_id` 외의 계정에는 적용하지 않는다(`allowed_account_ids`). 프로필을 잘못 골라도 plan 단계에서 멈춘다.
- AMI·`user_data`·키 페어 공개 키는 처음 만들 때만 쓴다(`ignore_changes`). 바꿔도 서버를 다시 만들지 않는다.

## devcos 계정: 코드가 실제 구성과 같은지 확인

devcos 계정은 AWS 변경을 콘솔로 한다. 여기서는 plan으로 비교만 하고 apply는 하지 않는다.

```bash
cd infra/terraform
cp devcos.tfvars.example devcos.tfvars    # <...>를 채운다
terraform init
terraform workspace new devcos           # 이미 있으면 select
AWS_PROFILE=devcos terraform plan -var-file=devcos.tfvars
```

결과가 `Plan: 29 to import, 0 to add, 0 to change, 0 to destroy.`이면 코드와 실제 구성이 같다. 콘솔에서 설정을 바꿨다면 이 plan에 차이로 나오니, 코드나 `devcos.tfvars.example`을 같이 고친다.

## 새 계정으로 옮기기

1. **AWS 리소스 만들기**

   ```bash
   ssh-keygen -t ed25519 -f ~/.ssh/hapbang-ec2     # 레포 밖. 공개 키를 tfvars의 public_key에 넣는다
   cp new-account.tfvars.example <이름>.tfvars      # <...>를 채운다
   terraform workspace new <이름>
   AWS_PROFILE=<프로필> terraform plan -var-file=<이름>.tfvars    # 29 to add 확인
   AWS_PROFILE=<프로필> terraform apply -var-file=<이름>.tfvars
   terraform output                                  # public_ips, bucket_name
   ```

2. **DNS**: DuckDNS에서 prod 도메인을 `public_ips`의 `server` 주소로 바꾼다.
3. **서버 초기화 확인**: `ssh -i ~/.ssh/hapbang-ec2 ubuntu@<server public_ip> cloud-init status --wait`가 `status: done`이면 `user_data.sh`가 끝난 것이다. 실패하면 `/var/log/cloud-init-output.log`를 본다.
4. **서버 설정**: `security.tf`는 22·80·443만 연다. k3s 설치 전에 콘솔에서 같은 보안 그룹을 source로 하는 node 사이 규칙(TCP 6443·10250, UDP 8472)과 kubectl 사용자 IP `/32`의 TCP 6443을 더한다([Network와 외부 노출](../../docs/deploy/aws-k3s.md#network와-외부-노출)). 없으면 agent가 server의 6443에 붙지 못한다. 그다음 [k3s 설치](../../docs/deploy/aws-k3s.md#k3s-설치) 뒤 [적용 순서](../../docs/deploy/k8s.md#적용-순서)대로 cluster·platform·Gateway를 적용한다. 새 Sealed Secrets controller는 기존 SealedSecret을 풀 수 없으므로, platform 적용 전에 백업한 controller 키를 복원하거나([키 백업](../../docs/deploy/aws-k3s.md#sealed-secrets-키-백업)) `postgres-credentials`를 새 키로 다시 봉인한다. 그다음 앱 SealedSecret을 다시 봉인한다(앱 `DB_PASSWORD`는 cluster의 `postgres-credentials`에서 읽는다). 앱 Secret의 `S3_BUCKET`은 `bucket_name` output이다. 배포용 IAM role·SSM 권한을 만들고 GitHub Environment `prod`의 `AWS_DEPLOY_ROLE_ARN`·`DEPLOY_INSTANCE_ID`를 새 값으로 바꾼다.
5. **데이터 이전**: k3s PostgreSQL의 백업·복원 절차는 아직 없다([배포 개요](../../docs/deploy/README.md#미정-사항-팀-결정-필요)). S3 객체는 기존 버킷에서 내려받아 새 버킷의 같은 key(`dev/…`, `prod/…`)로 올린다. DB에는 환경 prefix를 뺀 key가 저장되어 있으므로 key를 바꾸지 않는다.
6. **비용**: devcos 계정의 09:00~18:00 자동 시작·중지는 새 계정에 없다. 서버는 계속 켜져 있다.
