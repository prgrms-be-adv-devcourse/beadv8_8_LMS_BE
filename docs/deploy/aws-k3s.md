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
| `t3.large` | server/control plane | `hapbang.io/pool=core-data` | 외부 진입점, PostgreSQL, stateful middleware |
| `t3.medium` | agent | `hapbang.io/pool=app-observe` | application, Batch, React, 관측성 workload |

`core-data` 전체를 `NoSchedule`로 taint하지 않는다. Spring application과 Quartz replica 하나를 large에도 배치해 pod 분산과 rolling update를 시연해야 하기 때문이다.

## Workload 배치

| Workload | 기본 node | 배치 규칙 |
| --- | --- | --- |
| k3s server, Traefik | large | 외부 HTTPS 진입과 control plane |
| PostgreSQL | large | `core-data` required node affinity |
| Kafka, Valkey, Elasticsearch | large | 도입 시 `core-data` required node affinity |
| Spring application | 양쪽 | replica 2개를 hostname topology spread로 한 개씩 배치 |
| Quartz scheduler | 양쪽 | replica 2개를 한 개씩 배치. Quartz JDBC clustering 사용 |
| Spring Batch 실행 pod | medium 우선 | `app-observe` preferred affinity. checkpoint와 재시작은 DB가 보장 |
| React Nginx | medium 우선 | stateless workload |
| Prometheus·Loki·Tempo·Grafana | medium | 제한형 관측성 profile과 비교 기준선 |

StatefulSet과 local volume을 다른 node로 자동 이동할 수 있다고 가정하지 않는다. large 장애 시 PostgreSQL과 Elasticsearch가 함께 중단되므로 이 구성은 고가용성이 아니다. 두 node의 목적은 workload 분리, pod replica, rolling update와 장애 관찰이며 node 장애 무중단을 보장하는 것이 아니다.

## Network와 외부 노출

```text
Client
  → t3.large EIP :80/:443
  → Traefik
  → cluster Service
  → large/medium의 application pod
```

- 서비스 DNS는 large EIP만 가리킨다.
- medium EIP는 node 관리와 outbound 통신용이다. application 진입점으로 사용하지 않는다.
- PostgreSQL 5432, Kafka 9092, Valkey 6379, Elasticsearch 9200, Grafana 3000을 인터넷에 공개하지 않는다.
- k3s node 간 통신은 public IP가 아니라 private IP를 사용한다.
- 공통 cluster security group은 같은 security group을 source로 다음 inbound만 허용한다.
  - TCP 6443: agent → server API
  - UDP 8472: Flannel VXLAN, node 상호 간
  - TCP 10250: kubelet·metrics, node 상호 간
- 80·443 inbound security group은 large에만 연결한다.
- 22번 SSH의 전체 공개는 현재 위험으로 기록하고, 운영 경로를 확정하면 허용 source 제한 또는 SSM 전환을 별도 티켓으로 처리한다.

## S3와 IAM

파일 업로드는 Spring application이 받은 stream을 `global/storage`의 `S3FileStorage`로 전달하는 서버 경유 방식이다. application replica가 양쪽 node에서 동일하게 동작하도록 두 EC2에 같은 S3 접근 instance role을 연결한다.

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
2. large에 k3s server를 설치하고 `core-data` label을 적용한다.
3. medium을 agent로 join하고 `app-observe` label을 적용한다.
4. local k3d에서 검증한 Kustomize manifest를 dev namespace부터 적용한다.
5. application과 Quartz replica가 node별로 분산됐는지 확인한다.
6. stateful workload가 large 외 node에 배치되지 않는 구조 test를 둔다.
7. rolling update, pod 종료, medium node 중지와 large node 중지의 서로 다른 결과를 기록한다.
8. prod 전환과 rollback 검증이 끝난 뒤에만 Compose 배포 경로를 제거한다.
