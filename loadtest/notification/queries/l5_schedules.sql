-- N15 L5: 출연 요청 만료 임박 예약 행(id, 대상, 받는 사람, 상태, 보낼 시각 epoch, 끝난 시각 epoch, 실패 횟수). verify_n15.py l5가 읽는다
SELECT id, target_id, receiver_user_id, status, extract(epoch FROM send_at), coalesce(extract(epoch FROM processed_at)::text, ''),
       failure_count
FROM notification.notification_schedule
WHERE kind = 'APPEARANCE_REQUEST_EXPIRING'
ORDER BY id;
