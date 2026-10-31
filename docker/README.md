# docker/

배포·운영 문서는 [docs/deploy/](../docs/deploy/README.md)에 있다. 서버는 EC2 k3s이고, 앱 이미지는 앱마다 `apps/<앱>/Dockerfile`로 만든다.

| 파일 | 역할 |
| --- | --- |
| `compose.yaml`, `.env.example` | 로컬 개발용 PostgreSQL·S3Mock / 키 목록(예시 값) |
