-- N15 L5: 출연 요청 만료 임박 알림 수(대상별, 밀려난 행 포함). 같은 대상 2건 이상이면 중복, 0이면 누락이다
SELECT source_target_id, count(*)
FROM notification.notification
WHERE source_event_type = 'APPEARANCE_REQUEST_EXPIRING'
GROUP BY source_target_id;
