# AWS 2노드 k3s 배치

상태: k3s cluster(server 1 + agent 1) 구성 완료. application workload는 아직 Docker Compose로 운영한다.
현재 AWS 현황 기준일: 2026-10-28

현재 Docker Compose 운영 절차는 [배포 개요](README.md)를 따른다. 이 문서는 Compose를 k3s로 전환할 때 적용할 AWS 범위, node 역할과 workload 배치 규칙을 정의하고, 설치한 k3s cluster의 구성과 재설치 절차를 기록한다. workload를 옮기기 전에는 아래 workload 배치를 이미 배포된 상태로 표현하지 않는다.

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
| `t3.medium` | agent | `hapbang.io/pool=app-observe` | 모듈 앱, React, 관측성 workload |

`core-data` 전체를 `NoSchedule`로 taint하지 않는다. 모듈 앱 일부를 large에도 배치해야 두 node의 자원 예산 안에 들어가기 때문이다.

## Workload 배치

| Workload | 기본 node | 배치 규칙 |
| --- | --- | --- |
| k3s server, Traefik | large | 외부 HTTPS 진입과 control plane |
| PostgreSQL | large | `core-data` required node affinity |
| Kafka, Redis, Elasticsearch | large | 도입 시 `core-data` required node affinity |
| 모듈 앱 6개 | 양쪽 | 앱마다 replica 1개. large에 member·billing·appearance, medium에 lecture·chat·customer-support를 두고 시작한다([자원 예산](../architecture/final-project-infrastructure.md#ec2-2노드-자원-예산)) |
| React Nginx | medium 우선 | stateless workload |
| Prometheus·Loki·Tempo·Grafana | medium | 제한형 관측성 profile과 비교 기준선 |

StatefulSet과 local volume을 다른 node로 자동 이동할 수 있다고 가정하지 않는다. large 장애 시 PostgreSQL과 Elasticsearch가 함께 중단되므로 이 구성은 고가용성이 아니다. 두 node의 목적은 workload 분리, rolling update와 장애 관찰이며 node 장애 무중단을 보장하는 것이 아니다.

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
- PostgreSQL 5432, Kafka 9092, Redis 6379, Elasticsearch 9200, Grafana 3000을 인터넷에 공개하지 않는다.
- k3s node 간 통신은 public IP가 아니라 private IP를 사용한다.
- 공통 cluster security group은 같은 security group을 source로 다음 inbound만 허용한다.
  - TCP 6443: agent → server API
  - UDP 8472: Flannel VXLAN, node 상호 간
  - TCP 10250: kubelet·metrics, node 상호 간
- 80·443 inbound security group은 large에만 연결한다. 현재는 두 EC2가 security group 하나를 함께 써서 medium에도 80·443이 열려 있다. 분리는 별도 티켓으로 처리한다.
- 6443은 인터넷에 열지 않는다. 로컬에서는 SSH tunnel로 접근한다([로컬 kubectl 접근](#로컬-kubectl-접근)).
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

1~3은 2026-10-28에 끝냈다. 절차는 아래 [k3s 설치](#k3s-설치)에 있다.

1. (완료) 두 EC2의 private IP 통신, security group, IMDSv2와 IAM role을 검증한다.
2. (완료) large에 k3s server를 설치하고 `core-data` label을 적용한다.
3. (완료) medium을 agent로 join하고 `app-observe` label을 적용한다.
4. local k3d에서 검증한 Kustomize manifest를 dev namespace부터 적용한다.
5. 모듈 앱이 정한 node에 배치됐는지 확인한다.
6. stateful workload가 large 외 node에 배치되지 않는 구조 test를 둔다.
7. rolling update, pod 종료, medium node 중지와 large node 중지의 서로 다른 결과를 기록한다.
8. prod 전환과 rollback 검증이 끝난 뒤에만 Compose 배포 경로를 제거한다.

## k3s 설치

2026-10-28에 아래 구성으로 설치했다. 재설치할 때도 같은 순서를 따른다. 명령은 `ubuntu` 계정으로 실행한다.

| 항목 | 값 |
| --- | --- |
| 버전 | `v1.36.5+k3s1`. 두 node와 로컬 k3d가 같은 버전을 쓴다 |
| servicelb | 끈다. 80·443은 Compose의 edge nginx가 받는다 |
| graceful node shutdown | `shutdownGracePeriod` 60s, `shutdownGracePeriodCriticalPods` 20s |
| logind `InhibitDelayMaxSec` | 90s |
| 시간대 | 두 node 모두 `Asia/Seoul` |

### 1. 두 node 공통: logind와 kubelet 설정

kubelet graceful shutdown은 kubelet CLI 플래그가 없어서 `kubelet-arg`로 넘기면 kubelet이 시작하지 못한다. k3s가 읽는 kubelet drop-in 디렉터리에 설정 파일을 둔다([k3s 문서](https://docs.k3s.io/installation/configuration#kubelet-configuration-files), v1.32 이상).

```bash
sudo timedatectl set-timezone Asia/Seoul

sudo mkdir -p /etc/systemd/logind.conf.d
sudo tee /etc/systemd/logind.conf.d/zz-hapbang-inhibit-delay.conf >/dev/null <<'CONF'
[Login]
InhibitDelayMaxSec=90
CONF
sudo systemctl kill -s HUP systemd-logind
busctl get-property org.freedesktop.login1 /org/freedesktop/login1 org.freedesktop.login1.Manager InhibitDelayMaxUSec
# → t 90000000

sudo mkdir -p /var/lib/rancher/k3s/agent/etc/kubelet.conf.d
sudo tee /var/lib/rancher/k3s/agent/etc/kubelet.conf.d/10-graceful-shutdown.conf >/dev/null <<'CONF'
apiVersion: kubelet.config.k8s.io/v1beta1
kind: KubeletConfiguration
shutdownGracePeriod: 60s
shutdownGracePeriodCriticalPods: 20s
CONF
```

logind 파일 이름을 `zz-`로 시작하는 이유: Ubuntu의 unattended-upgrades가 `/usr/lib/systemd/logind.conf.d/unattended-upgrades-logind-maxdelay.conf`에서 30초를 정한다. logind는 drop-in을 디렉터리와 관계없이 파일 이름 순서로 적용하므로, kubelet이 쓰는 `99-kubelet.conf`(60초)는 이 파일에 덮인다. 그러면 kubelet은 `Failed to start node shutdown manager`를 남기고 shutdown 잠금을 잡지 않는다. 적용 순서는 `systemd-analyze cat-config systemd/logind.conf`로 확인한다.

### 2. large: server

```bash
sudo mkdir -p /etc/rancher/k3s
sudo tee /etc/rancher/k3s/config.yaml >/dev/null <<'CONF'
node-ip: <large private IP>
advertise-address: <large private IP>
node-label:
  - hapbang.io/pool=core-data
disable:
  - servicelb
write-kubeconfig-mode: "0600"
CONF

sudo grep -q servicelb /etc/rancher/k3s/config.yaml && sudo grep -q core-data /etc/rancher/k3s/config.yaml \
  && curl -sfL https://get.k3s.io | INSTALL_K3S_VERSION='v1.36.5+k3s1' sh -s - server
```

설치 명령은 config 확인 조건 뒤에만 실행한다. 2026-10-28 첫 설치는 config가 적용되지 않아 servicelb가 켜진 상태였다. 그때 large에서는 `docker-proxy`가 80·443을 계속 listen하고 있었는데, 밖에서는 Traefik 404와 `TRAEFIK DEFAULT CERT`가 돌아왔다. k3s를 uninstall하자 edge nginx 응답이 돌아왔다. 당시 svclb pod 상태는 기록하지 못했다. hostPort는 socket을 bind하지 않고 CNI portmap의 iptables DNAT 규칙으로 구현되므로, Docker가 같은 port를 쓰고 있어도 scheduler는 충돌을 모르고 svclb pod를 배치한다. 그래서 DNAT 규칙이 docker-proxy보다 먼저 요청을 받은 것으로 본다.

설치 뒤 확인한다. 밖에서 prod·dev 응답(http 301, `/actuator/health` 200, Let's Encrypt 인증서)도 함께 본다.

```bash
sudo kubectl get nodes -L hapbang.io/pool          # POOL: core-data
sudo kubectl -n kube-system get pods | grep svclb  # 출력 없음
sudo kubectl -n kube-system get svc traefik        # EXTERNAL-IP: <pending>
```

### 3. medium: agent

join token은 large의 `/var/lib/rancher/k3s/server/node-token`이다. 레포·채팅·이슈에 남기지 않는다.

```bash
nc -zv <large private IP> 6443

sudo mkdir -p /etc/rancher/k3s
sudo install -m 600 /dev/null /etc/rancher/k3s/config.yaml   # token이 기본 권한으로 잠깐이라도 노출되지 않게 먼저 600으로 만든다
sudo tee /etc/rancher/k3s/config.yaml >/dev/null <<'CONF'
server: https://<large private IP>:6443
token: <node-token>
node-ip: <medium private IP>
node-label:
  - hapbang.io/pool=app-observe
CONF

sudo grep -qx 'server: https://<large private IP>:6443' /etc/rancher/k3s/config.yaml \
  && curl -sfL https://get.k3s.io | INSTALL_K3S_VERSION='v1.36.5+k3s1' sh -s - agent
```

- 반드시 `agent`로 설치한다. `server`로 설치하면 별도 cluster가 생긴다. 서비스 이름이 `k3s-agent`인지 확인한다.
- installer가 `Starting k3s-agent`에서 멈추면 `sudo journalctl -u k3s-agent -n 30`을 본다. `not authorized`는 token 불일치다. config의 `token:` 줄만 고치고 `sudo systemctl restart k3s-agent`로 다시 시작한다.

### 4. 검증

large에서 실행한다.

| 확인 | 명령 | 기대 결과 |
| --- | --- | --- |
| node 역할 | `sudo kubectl get nodes -o wide -L hapbang.io/pool` | 두 node `Ready`, medium `ROLES <none>`, INTERNAL-IP는 private IP, POOL이 각각 `core-data`·`app-observe` |
| node 간 pod 통신(VXLAN) | medium에 `nodeSelector`를 건 busybox pod에서 `nslookup kubernetes.default.svc.cluster.local` | `Address: 10.43.0.1` (CoreDNS는 large에 있다) |
| pod의 S3 instance role | medium에 `nodeSelector`를 건 curl pod에서 IMDSv2 token을 받아 `meta-data/iam/security-credentials/` 조회 | instance role 이름 |
| graceful shutdown | 각 node에서 `systemd-inhibit --list \| grep -i kubelet` | `kubelet ... shutdown ... delay` |

검증 pod는 `--rm`으로 만들고 끝나면 지워진다.

```bash
# node 간 pod 통신
sudo kubectl run net-test --rm -it --restart=Never --image=busybox:1.36 \
  --overrides='{"spec":{"nodeSelector":{"hapbang.io/pool":"app-observe"}}}' \
  -- nslookup kubernetes.default.svc.cluster.local

# pod의 S3 instance role
sudo kubectl run imds-test --rm -it --restart=Never --image=curlimages/curl:8.10.1 \
  --overrides='{"spec":{"nodeSelector":{"hapbang.io/pool":"app-observe"}}}' \
  -- sh -c 'T=$(curl -s -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60"); curl -s -H "X-aws-ec2-metadata-token: $T" http://169.254.169.254/latest/meta-data/iam/security-credentials/; echo'
```

busybox `nslookup`은 `kubernetes.default` 같은 짧은 이름에 search domain을 붙이지 못해 `NXDOMAIN`을 낸다. 전체 이름으로 확인한다.

### 되돌리기

large는 `sudo /usr/local/bin/k3s-uninstall.sh`, medium은 `sudo /usr/local/bin/k3s-agent-uninstall.sh`를 실행한다. uninstall은 `KUBE-`·`CNI-`·flannel iptables 규칙만 지우고 Docker 규칙은 남긴다. `/etc/rancher/k3s`와 `/var/lib/rancher/k3s`도 지우므로 재설치 때 config와 kubelet drop-in을 다시 만든다. logind의 `zz-` 파일은 남는다.

- medium만 다시 join할 때는 uninstall 전에 large에서 `sudo kubectl delete node <medium node 이름>`을 먼저 실행한다. 그러지 않으면 같은 hostname의 node password가 cluster에 남아 재join이 거절된다.
- uninstall은 `/var/lib/rancher/k3s`의 local-path volume도 지운다. stateful workload(PostgreSQL·Kafka·Elasticsearch)를 올린 뒤에는 drain과 backup을 먼저 하지 않으면 데이터를 되살릴 수 없다.

## 로컬 kubectl 접근

6443을 인터넷에 열지 않고 SSH local forward로 large의 API 서버에 접근한다. k3s API 서버 인증서에는 `127.0.0.1`이 SAN으로 들어 있어 tunnel로도 TLS 검증이 된다.

- large의 `/etc/rancher/k3s/k3s.yaml`은 cluster-admin 자격 증명이다. 로컬에 받으면 권한을 `600`으로 두고 레포·채팅에 올리지 않는다.
- kubeconfig의 cluster·context·user 이름이 모두 `default`라서, 다른 kubeconfig와 합치기 전에 이름을 바꾼다.
- instance가 18:00에 중지되면 tunnel도 끊긴다. 서버가 켜진 뒤 다시 연다.
- 로컬 파일은 server의 원본과 따로 논다. k3s client 인증서가 갱신되거나 만료되면(기본 1년) 1~3단계를 다시 한다.

```bash
# 1) kubeconfig 받기 (SSH 접속 방법은 각자의 키·Host 설정을 쓴다)
(umask 077; ssh ubuntu@<large EIP> 'sudo cat /etc/rancher/k3s/k3s.yaml' > ~/.kube/hapbang-k3s.yaml)

# 2) 이름을 hapbang-k3s로, 주소를 tunnel 포트(16443)로 바꾼다 (macOS sed)
sed -E -i '' \
  -e 's/^( *(- )?(name|cluster|user|current-context)): default$/\1: hapbang-k3s/' \
  -e 's#https://127.0.0.1:6443#https://127.0.0.1:16443#' \
  ~/.kube/hapbang-k3s.yaml

# 3) 기존 kubeconfig에 합친다 (kubectx는 KUBECONFIG에 여러 파일을 나열하는 방식을 지원하지 않는다)
cp ~/.kube/config ~/.kube/config.bak
(umask 077; KUBECONFIG=~/.kube/config:~/.kube/hapbang-k3s.yaml kubectl config view --flatten > ~/.kube/config.merged) \
  && kubectl --kubeconfig ~/.kube/config.merged config get-contexts hapbang-k3s \
  && mv ~/.kube/config.merged ~/.kube/config

# 4) tunnel을 열고 context를 바꾼다
ssh -fN -L 16443:127.0.0.1:6443 ubuntu@<large EIP>
kubectx hapbang-k3s
kubectl get nodes -L hapbang.io/pool

# tunnel 닫기
pkill -f '16443:127.0.0.1:6443'
```
