# 배포 개요

합방은 EC2 2노드 k3s(large server + medium agent)에서만 운영한다. 외부 80·443은 large의 ServiceLB → Envoy Gateway가 받고, TLS 인증서는 cert-manager가 Let's Encrypt에서 발급·갱신한다(2026-10-30, #372). 공용 인프라(PostgreSQL·Kafka·Redis·Elasticsearch·관측성)는 `hapbang-platform` namespace에 있다. FE `web`은 `/` route에 연결됐다. 업무 앱은 route가 없어 업무 API는 404다(member-app·lecture-app은 2026-10-30 릴리스 전 배포로 떠 있지만 외부에 열리지 않는다).

기존 Docker Compose(prod·dev app·web·PostgreSQL과 edge nginx·certbot)는 2026-10-30에 컨테이너와 볼륨까지 모두 지웠다(#440). 기존 운영 DB는 백업하지 않았다. 필요 없는 데이터만 있어 k3s PostgreSQL에서 새로 시작한다. dev 서버는 없다. 개발 확인은 로컬과 k3d에서 한다([로컬 개발](../dev/local.md)).

| 대상 | 주소 | 상태 |
| --- | --- | --- |
| prod | https://hapbang.duckdns.org | FE `web`만 route에 연결됐다. member-app·lecture-app은 릴리스 전 배포로 떠 있지만 route가 없다(2026-10-30, [k8s.md](k8s.md#릴리스-전-배포)). 나머지 앱은 미배포. `main`의 앱 이미지를 수동 배포한다 |
| Grafana | https://hapbang.duckdns.org/grafana/ | Grafana 자체 로그인 |
| Kafka UI | https://hapbang.duckdns.org/kafka/ | Envoy Basic Auth |

## 문서

| 문서 | 내용 |
| --- | --- |
| [env-vars.md](env-vars.md) | 환경변수 추가·변경·삭제 절차 |
| [security.md](security.md) | 자격 증명 관리, 공개 레포 원칙, 유출 시 대응 |
| [s3.md](s3.md) | 파일 업로드(S3) 구조, 버킷·IAM 역할 설정, 확인 절차, 로컬 S3Mock |
| [gateway.md](gateway.md) | Envoy Gateway·TLS·내부 포트 분리·Grafana와 로컬 검증, EC2 적용 기록 |
| [aws-k3s.md](aws-k3s.md) | 최종 프로젝트 AWS 2노드 k3s 역할, workload 배치, network·IAM·storage, 전환 순서, 설치·로컬 kubectl 접근, Sealed Secrets 키 백업 |
| [k8s.md](k8s.md) | `k8s/` manifest 구조, 차트 버전, Secret 목록, 적용 순서, 운영 앱 배포, 로컬 k3d 검증 |
| [infra/terraform](../../infra/terraform/README.md) | AWS 리소스(EC2·네트워크·S3·IAM) 코드, 다른 계정으로 옮기기 |

## 배포 흐름

```
PR → GitHub Actions(CI)
  verify  앱 목록 검사 → verifyModuleStructure → 앱별 check bootJar → 앱별 이미지 빌드 확인(push 안 함)

main push → Publish images(publish.yml)   # 릴리스 전에는 사람이 write:packages PAT로 직접 push(k8s.md "릴리스 전 배포")
  바뀐 앱만 GHCR(private, 조직 정책)에 ghcr.io/prgrms-be-adv-devcourse/beadv8_8_lms_be/<앱>:sha-<커밋> 발행

Actions → Deploy(deploy.yml) → Run workflow(main)
  바뀐 앱만 SSM Run Command로 large에서 overlay apply → rollout status, 실패하면 그 앱만 rollout undo
  node는 공유 Secret ghcr-pull(k8s/registry)로 이미지를 받는다(k8s.md "GHCR pull 인증")
```

- 앱 배포 절차와 롤백은 [운영 앱 배포](k8s.md#운영-앱-배포)를 따른다. CI는 PR에서만 돌고 dev 병합 때는 workflow가 없다.
- `k8s/cluster`·`k8s/platform`·`k8s/gateway`는 자동 배포하지 않는다. [적용 순서](k8s.md#적용-순서)대로 수동 적용한다.
- FE의 k3s 배포는 #374에서 연결한다. FE 레포의 기존 배포 workflow는 서버의 Compose web을 교체하던 방식이라 더 쓰지 않는다.
- **운영 시간**: EC2는 09:00~18:00에 자동으로 켜져 있다. 켜지면 k3s와 pod가 다시 뜬다. 그 외 시간에는 SSM 배포가 실패하므로 서버가 켜진 뒤 실행한다. 18:00 이후 작업이 필요하면 AWS 콘솔에서 인스턴스를 직접 시작하고, 끝나면 중지한다.

## 자주 하는 작업

| 하고 싶은 것 | 방법 |
| --- | --- |
| 운영 반영 | `dev` → `main` PR 병합 → Publish images 성공 확인 → Actions → Deploy 실행([운영 앱 배포](k8s.md#운영-앱-배포)) |
| 롤백 | Deploy를 `apps`에 앱, `sha`에 되돌릴 main 커밋을 넣어 실행. 그 커밋의 SealedSecret도 함께 돌아가므로 비밀값 교체·DB 마이그레이션 이전 커밋으로는 되돌리지 않는다([운영 앱 배포](k8s.md#운영-앱-배포)) |
| 환경변수 추가 | [env-vars.md](env-vars.md) |
| DB 스키마 변경 | Flyway 마이그레이션 SQL 추가, 앱 시작 시 자동 적용 → [db-migration.md](../db-migration.md) |
| 공용 인프라 변경 | `k8s/` manifest 수정 → PR 병합 → 수동 적용([적용 순서](k8s.md#적용-순서)) |
| cluster 상태 확인 | `kubectl --context hapbang-k3s get pods -A`([로컬 kubectl 접근](aws-k3s.md#로컬-kubectl-접근)) |

## 미정 사항 (팀 결정 필요)

| 항목 | 현재 상태 |
| --- | --- |
| DB 백업 | k3s PostgreSQL의 자동 백업 없음. PVC는 Retain StorageClass지만 node 장애·uninstall에는 보호되지 않는다([aws-k3s.md](aws-k3s.md#되돌리기)) |
| prod 배포 승인 | Deploy 수동 실행(실행하는 사람이 곧 승인). GitHub Environment `prod`는 `main`만 허용하고 승인자는 두지 않는다([security.md](security.md#알려진-위험)) |
