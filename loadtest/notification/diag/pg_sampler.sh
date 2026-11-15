#!/bin/sh
# B0 처리량 변동 진단(N13): PostgreSQL pod 안에서 1초마다 diag_pg.sql을 돌린다(활동·대기·막는 pid·행 잠금이 걸린 회원).
# 정지 파일(/tmp/b0/diag.stop)이 생기거나 기한(초)이 지나면 끝난다. 사용: pg_sampler.sh <기한 초>
end=$(( $(date +%s) + $1 ))
rm -f /tmp/b0/diag.stop
while [ ! -e /tmp/b0/diag.stop ] && [ "$(date +%s)" -lt "$end" ]; do
  psql -U postgres -d auxiliary -At -F "$(printf '\t')" -f /tmp/b0/diag_pg.sql
  sleep 1
done
