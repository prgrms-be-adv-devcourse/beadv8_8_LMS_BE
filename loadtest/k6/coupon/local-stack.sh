#!/usr/bin/env bash
# 쿠폰 부하·장애 시나리오용 로컬 인프라와 lecture-app (#813). 결과·절차는 docs/load-test/coupon-issue.md.
#
# docker/compose.yaml(개발용)과 컨테이너·볼륨·포트를 나눠, 장애 주입(docker stop·pause)이 개발 환경을 건드리지 않게 한다.
# Redis는 운영 platform(k8s/platform/base/redis.yaml)과 같은 인자(AOF everysec, maxmemory 48mb, noeviction)로 띄운다.
# 운영(hapbang.duckdns.org, kubectl context hapbang-k3s)에는 아무것도 보내지 않는다.
#
# 사용법:
#   loadtest/k6/coupon/local-stack.sh up              # PostgreSQL·Redis·Kafka·S3Mock 컨테이너를 띄운다
#   loadtest/k6/coupon/local-stack.sh app <app.jar>   # lecture-app을 이 인프라에 붙여 앞에서 실행한다(로그는 표준 출력)
#   loadtest/k6/coupon/local-stack.sh psql [SQL]      # 로컬 lecture DB에 psql
#   loadtest/k6/coupon/local-stack.sh redis-cli ...   # 로컬 Redis에 redis-cli
#   loadtest/k6/coupon/local-stack.sh down            # 컨테이너와 볼륨을 지운다
#
# 장애 주입은 이 스크립트가 띄운 컨테이너(hapbang-lt-*)에만 한다. 예: docker stop hapbang-lt-redis, docker pause hapbang-lt-postgres
set -euo pipefail

PREFIX="hapbang-lt"
NETWORK="${PREFIX}"
PG_PORT="${LT_PG_PORT:-25432}"
REDIS_PORT="${LT_REDIS_PORT:-26379}"
KAFKA_PORT="${LT_KAFKA_PORT:-29092}"
S3_PORT="${LT_S3_PORT:-29090}"
APP_PORT="${LT_APP_PORT:-18084}"
# 로컬 전용 값이다. 운영 값과 관계없다
PG_DB="lecture"
PG_USER="example"
PG_PASSWORD="example"

usage() {
    sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
}

up() {
    docker network inspect "$NETWORK" >/dev/null 2>&1 || docker network create "$NETWORK" >/dev/null
    docker run -d --name "${PREFIX}-postgres" --network "$NETWORK" \
        -e POSTGRES_DB="$PG_DB" -e POSTGRES_USER="$PG_USER" -e POSTGRES_PASSWORD="$PG_PASSWORD" \
        -p "127.0.0.1:${PG_PORT}:5432" -v "${PREFIX}-postgres:/var/lib/postgresql" postgres:18 >/dev/null
    docker run -d --name "${PREFIX}-redis" --network "$NETWORK" -p "127.0.0.1:${REDIS_PORT}:6379" \
        -v "${PREFIX}-redis:/data" redis:8 \
        redis-server --appendonly yes --appendfsync everysec --save "" \
        --maxmemory 48mb --maxmemory-policy noeviction --protected-mode no >/dev/null
    # docker/compose.yaml의 kafka와 같은 단일 노드 KRaft. 호스트의 앱이 붙도록 광고 주소를 바꾼 포트로 둔다
    docker run -d --name "${PREFIX}-kafka" --network "$NETWORK" -p "127.0.0.1:${KAFKA_PORT}:9092" \
        -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
        -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
        -e KAFKA_ADVERTISED_LISTENERS="PLAINTEXT://localhost:${KAFKA_PORT}" \
        -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
        -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
        -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT -e CLUSTER_ID=kPETokImQqe8usYUIfbj1A \
        -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
        -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 -e KAFKA_SHARE_COORDINATOR_STATE_TOPIC_REPLICATION_FACTOR=1 \
        -e KAFKA_SHARE_COORDINATOR_STATE_TOPIC_MIN_ISR=1 -e KAFKA_MIN_INSYNC_REPLICAS=1 \
        -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=false \
        -e KAFKA_LOG_DIRS=/var/lib/kafka/data -v "${PREFIX}-kafka:/var/lib/kafka/data" apache/kafka:4.4.0 >/dev/null
    docker run -d --name "${PREFIX}-s3mock" --network "$NETWORK" -p "127.0.0.1:${S3_PORT}:9090" \
        -e COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS=hapbang-local adobe/s3mock:5.2.3 >/dev/null
    echo "PostgreSQL localhost:${PG_PORT}/${PG_DB}, Redis :${REDIS_PORT}, Kafka :${KAFKA_PORT}, S3Mock :${S3_PORT}"
}

app() {
    local jar="${1:?app.jar 경로를 준다}"
    # 운영 lecture-app과 heap 상한·GC를 맞춘다(-Xmx224m, 운영은 CPU 2개 이하라 JVM이 Serial GC를 고른다).
    # 노트북 CPU 수를 그대로 쓰지 않도록 t3.medium(2 vCPU)에 맞춘다. LT_JAVA_OPTS로 JFR 등을 더한다
    export JAVA_TOOL_OPTIONS="-Xmx224m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 ${LT_JAVA_OPTS:-}"
    export SPRING_PROFILES_ACTIVE=local
    export SPRING_DOCKER_COMPOSE_ENABLED=false
    export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:${PG_PORT}/${PG_DB}"
    export SPRING_DATASOURCE_USERNAME="$PG_USER"
    export SPRING_DATASOURCE_PASSWORD="$PG_PASSWORD"
    export SPRING_DATA_REDIS_HOST=localhost
    export SPRING_DATA_REDIS_PORT="$REDIS_PORT"
    export SPRING_KAFKA_BOOTSTRAP_SERVERS="localhost:${KAFKA_PORT}"
    # 로컬 프로필은 Kafka 리스너·토픽 생성을 끈다. 발급 확정 소비가 돌아야 하므로 켠다(docs/dev/local.md)
    export SPRING_KAFKA_LISTENER_AUTO_STARTUP=true
    export SPRING_KAFKA_ADMIN_AUTO_CREATE=true
    export S3_ENDPOINT="http://localhost:${S3_PORT}"
    export SERVER_PORT="$APP_PORT"
    exec java -jar "$jar"
}

case "${1:-}" in
    up) up ;;
    app) shift; app "$@" ;;
    psql) shift; docker exec -i "${PREFIX}-postgres" psql -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 ${1:+-c "$1"} ;;
    redis-cli) shift; docker exec -i "${PREFIX}-redis" redis-cli "$@" ;;
    down)
        docker rm -f "${PREFIX}-postgres" "${PREFIX}-redis" "${PREFIX}-kafka" "${PREFIX}-s3mock" >/dev/null 2>&1 || true
        docker volume rm "${PREFIX}-postgres" "${PREFIX}-redis" "${PREFIX}-kafka" >/dev/null 2>&1 || true
        docker network rm "$NETWORK" >/dev/null 2>&1 || true
        ;;
    *) usage ;;
esac
