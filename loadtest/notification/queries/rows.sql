-- V6 받는 사람별 활성·Soft Delete 행 수
SELECT receiver_user_id, count(*) FILTER (WHERE deleted_at IS NULL), count(*) FILTER (WHERE deleted_at IS NOT NULL)
FROM notification.notification GROUP BY receiver_user_id ORDER BY receiver_user_id;
