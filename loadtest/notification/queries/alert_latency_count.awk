# 알림 토픽 5개의 성공 처리 건수(consume.latency count) 합(N13, ND-167·ND-168). 회원 토픽은 뺀다.
# b0.sh가 0.5초마다 Prometheus 본문을 받아 이 파일로 센다. verify_b0.alert_latency_count와 같은 값을 낸다(시험 AwkCountTest).
# 사용: curl … | awk -f alert_latency_count.awk
BEGIN {
  split("appearance.events.v1 billing.events.v1 chat.events.v1 customersupport.events.v1 lecture.events.v1", names, " ")
  for (i in names) alert["topic=\"" names[i] "\""] = 1
  total = 0
}
$1 ~ /^hapbang_notification_consume_latency_seconds_count\{/ {
  if (match($1, /topic="[^"]*"/)) {
    if (substr($1, RSTART, RLENGTH) in alert) total += $2
  }
}
END { printf "%d\n", total }
