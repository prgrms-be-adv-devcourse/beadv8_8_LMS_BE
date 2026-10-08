# docker/

배포·운영 문서는 [docs/deploy/](../docs/deploy/README.md)에 있다.

| 파일 | 역할 | 서버 반영 |
| --- | --- | --- |
| `compose.yaml`, `.env.example` | 로컬 개발용 PostgreSQL / 키 목록(예시 값) | — |
| `Dockerfile` | BE 이미지 (FE 이미지는 FE 레포 `Dockerfile`) | CI가 빌드 |
| `compose.prod.yaml`, `compose.dev.yaml` | 환경별 web(FE)·app(BE)·postgres. 외부 포트 없음 | 수동 |
| `compose.edge.yaml`, `nginx/` | 유일한 진입점(80·443), HTTPS·도메인·경로 라우팅, 인증서 갱신 | 수동 |
| `deploy.sh` | 서버 배포 스크립트(GitHub Actions가 배포 키로 실행) | 수동 |

**수동** 표시 파일을 바꾸면 병합과 함께 서버에 반영해야 한다 → [operations.md#설정-반영](../docs/deploy/operations.md#설정-반영)
