-- N15 L5: 상태별 예약 수(생성 확인·완료 대기)
SELECT status, count(*) FROM notification.notification_schedule
WHERE kind = 'APPEARANCE_REQUEST_EXPIRING' GROUP BY status ORDER BY status;
