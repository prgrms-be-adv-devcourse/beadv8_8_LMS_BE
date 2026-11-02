# docker/

배포·운영 문서는 [docs/deploy/](../docs/deploy/README.md)에 있다. 서버는 EC2 k3s이고, 앱 이미지는 앱마다 `apps/<앱>/Dockerfile`로 만든다.

| 파일 | 역할 |
| --- | --- |
| `compose.yaml`, `.env.example` | 로컬 개발용 PostgreSQL·S3Mock·Redis·Kafka·Envoy / 키 목록(예시 값) |
| `envoy/envoy.yaml` | 로컬 Envoy 설정. 운영 Gateway의 JWT 검증·사용자 헤더 전달을 재현한다([docs/deploy/gateway.md](../docs/deploy/gateway.md#로컬-envoy-508)) |
