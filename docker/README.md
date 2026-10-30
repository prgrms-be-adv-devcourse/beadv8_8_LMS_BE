# docker/

배포·운영 문서는 [docs/deploy/](../docs/deploy/README.md)에 있다. 서버는 EC2 k3s이고, 앱 이미지는 앱마다 `apps/<앱>/Dockerfile`로 만든다.

| 파일 | 역할 |
| --- | --- |
| `compose.yaml`, `.env.example` | 로컬 개발용 PostgreSQL·S3Mock / 키 목록(예시 값) |
| `Dockerfile`, `compose.prod.yaml`, `compose.dev.yaml`, `compose.edge.yaml`, `nginx/`, `deploy.sh` | 기존 단일 앱 Compose 배포용. 서버의 Compose는 2026-10-30에 지웠고(#440) 이 파일들은 #374에서 지운다. 서버에 반영하지 않는다 |
