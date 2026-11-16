-- V5 같은 원본 키(원본 이벤트 종류·대상 ID·받는 사람) 또는 같은 묶음 키의 행이 2개 이상인 경우
SELECT 'source', source_event_type, source_target_id, receiver_user_id, count(*) FROM notification.notification
WHERE source_target_id IS NOT NULL GROUP BY source_event_type, source_target_id, receiver_user_id HAVING count(*) > 1
UNION ALL
SELECT 'bundle', kind, bundle_key, receiver_user_id, count(*) FROM notification.notification
WHERE bundle_key IS NOT NULL GROUP BY kind, bundle_key, receiver_user_id HAVING count(*) > 1;
