# AWS 2노드 k3s 배치

상태: 최종 프로젝트 목표 구조
현재 AWS 현황 기준일: 2026-10-28

현재 Docker Compose 운영 절차는 [배포 개요](README.md)를 따른다. 이 문서는 Compose를 k3s로 전환할 때 적용할 AWS 범위, node 역할과 workload 배치 규칙을 정의한다. 전환 전에는 목표 구성을 이미 배포된 상태로 표현하지 않는다.

## 허용된 AWS 범위

- Region: 서울(`ap-northeast-2`)
- VPC 1개: `10.0.0.0/16`
- public subnet 1개: `ap-northeast-2a`
- EC2 2대
  - `t3.large`, Ubuntu 24.04, gp3 50 GB, EIP 1개
  - `t3.medium`, Ubuntu 24.04, gp3 50 GB, EIP 1개
- 사진·영상 업로드용 S3 bucket 1개
- EKS, RDS, ElastiCache, MSK, ELB 등 managed service와 추가 EC2는 범위 밖이다.

두 EC2는 **하나의 k3s cluster**를 구성한다. 다중 cluster로 나누지 않는다.

## Node 역할

| EC2 | k3s 역할 | node label | 주 역할 |
| --- | --- | --- | --- |
| `t3.large` | server/control plane | `hapbang.io/pool=core-data` | 외부 진입점(Envoy proxy), PostgreSQL, stateful middleware, 모듈 앱 일부 |
| `t3.medium` | agent | `hapbang.io/pool=app-observe` | 모듈 앱, React, controller·operator, 관측성 workload |

`core-data` 전체를 `NoSchedule`로 taint하지 않는다. 모듈 앱 일부를 large에도 배치해야 두 node의 자원 예산 안에 들어가기 때문이다.

## Workload 배치

| Workload | 기본 node | 배치 규칙 |
| --- | --- | --- |
| k3s server, ServiceLB, Envoy proxy | large | control plane과 외부 HTTPS 진입. k3s는 `--disable traefik`으로 설치하고 ServiceLB는 large에서만 켠다 |
| PostgreSQL | large | `core-data` required node affinity |
| Kafka, Redis, Elasticsearch | large | 도입 시 `core-data` required node affinity |
| 모듈 앱 8개 | 양쪽 | 앱마다 replica 1개. large에 member·billing·appearance·search(448 MiB), medium에 lecture·chat·customer-support·notification(384 MiB)을 두고 시작한다([자원 예산](../architecture/final-project-infrastructure.md#ec2-2노드-자원-예산)) |
| Envoy Gateway controller, cert-manager, ECK operator | medium | stateless controller |
| React 정적 서버 | medium | stateless workload |
| Prometheus·Loki·Tempo·Grafana | medium | 제한형 관측성 profile과 비교 기준선 |

StatefulSet과 local volume을 다른 node로 자동 이동할 수 있다고 가정하지 않는다. large 장애 시 PostgreSQL과 Elasticsearch가 함께 중단되므로 이 구성은 고가용성이 아니다. 두 node의 목적은 workload 분리, rolling update와 장애 관찰이며 node 장애 무중단을 보장하는 것이 아니다.

## Network와 외부 노출

```text
Client
  → t3.large EIP :80/:443
  → ServiceLB → Envoy proxy (TLS, JWT 서명 검증, route)
  → cluster Service
  → large/medium의 모듈 앱 pod
```

nginx는 쓰지 않는다. TLS 인증서는 cert-manager가 Let's Encrypt에서 발급·갱신한다. EC2에는 prod(main) 한 벌만 두고 dev 서버는 두지 않는다. 진입점과 인증 흐름은 [최종 인프라 설계 3장](../architecture/final-project-infrastructure.md#api-gateway--envoy-gateway)을 따른다.

- 서비스 DNS는 large EIP만 가리킨다.
- medium EIP는 node 관리와 outbound 통신용이다. application 진입점으로 사용하지 않는다.
- PostgreSQL 5432, Kafka 9092, Redis 6379, Elasticsearch 9200, Grafana 3000을 인터넷에 공개하지 않는다.
- k3s node 간 통신은 public IP가 아니라 private IP를 사용한다.
- 공통 cluster security group은 같은 security group을 source로 다음 inbound만 허용한다.
  - TCP 6443: agent → server API
  - UDP 8472: Flannel VXLAN, node 상호 간
  - TCP 10250: kubelet·metrics, node 상호 간
- 80·443 inbound security group은 large에만 연결한다.
- 22번 SSH의 전체 공개는 현재 위험으로 기록하고, 운영 경로를 확정하면 허용 source 제한 또는 SSM 전환을 별도 티켓으로 처리한다.

## S3와 IAM

파일 업로드는 앱이 받은 stream을 `S3FileStorage`로 전달하는 서버 경유 방식이다. 앱이 양쪽 node에서 동일하게 동작하도록 두 EC2에 같은 S3 접근 instance role을 연결한다.

- static access key를 pod에 주입하지 않는다.
- 두 EC2 모두 IMDSv2를 강제한다.
- container에서 instance metadata에 접근할 수 있도록 metadata response hop limit을 2로 둔다.
- S3 bucket은 사진·영상 업로드 전용이다. telemetry, vector index, PostgreSQL·Elasticsearch backup 용도로 임의 확장하지 않는다.

## Storage와 자원 원칙

- large의 50 GB에는 PostgreSQL, Kafka log와 Elasticsearch index가 함께 들어간다. 각 workload에 retention과 disk alert를 둔다.
- medium의 50 GB는 application image와 제한형 관측성 data에 사용한다.
- 관측성은 Prometheus·Alloy·Loki·Tempo·Grafana 한 벌만 운영한다. Mimir와 Elastic Observability를 추가하지 않는다.
- 두 T3 instance 모두 CPU credit을 부하 시험 결과와 함께 기록한다.

## Terraform 범위

Terraform은 새 토폴로지를 만드는 도구가 아니라 **현재 배포된 AWS 구성을 import-first로 코드화**한다.

- EC2 2대와 각 EBS·ENI·EIP
- VPC, subnet, Internet Gateway, route table
- security group과 node 간 self-reference rule
- S3 bucket과 EC2 instance role/profile

첫 완료 조건은 기존 자원을 다시 만들지 않는 `terraform plan` zero-change다. account ID, instance ID, public IP와 bucket 이름은 문서나 output에 직접 고정하지 않는다.

## 전환 순서

1. 두 EC2의 private IP 통신, security group, IMDSv2와 IAM role을 검증한다.
2. large에 k3s server를 `--disable traefik`으로 설치하고 `core-data` label과 ServiceLB 활성 label을 적용한다.
3. medium을 agent로 join하고 `app-observe` label을 적용한다.
4. `k8s/cluster`, `k8s/platform`을 수동으로 적용하고, local k3d에서 검증한 `k8s/apps/overlays/prod`를 적용한다. dev namespace는 만들지 않는다.
5. 모듈 앱이 정한 node에 배치됐는지 확인한다.
6. stateful workload가 large 외 node에 배치되지 않는 구조 test를 둔다.
7. rolling update, pod 종료, medium node 중지와 large node 중지의 서로 다른 결과를 기록한다.
8. prod 전환과 rollback 검증이 끝난 뒤에만 Compose 배포 경로, edge nginx와 dev 서버를 제거한다.
