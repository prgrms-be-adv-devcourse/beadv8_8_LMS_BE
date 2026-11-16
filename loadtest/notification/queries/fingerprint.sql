-- F 결과 지문 행. PK·생성·수정 시각·occurred_at·read_at·deleted_at·payload는 넣지 않는다(ND-156, N13).
-- 중복을 지우지 않는다(같은 행이 둘이면 두 줄). 정렬과 해시는 verify_b0.py가 한다
SELECT concat_ws(E'\t', kind, receiver_user_id::text, coalesce(source_event_type, ''), coalesce(source_target_id, ''),
       replace(replace(message, E'\t', ' '), E'\n', ' '), coalesce(target_type, ''), coalesce(target_id, ''),
       array_to_string(ARRAY(SELECT unnest(mentioned_user_ids) ORDER BY 1), ','), coalesce(bundle_key, ''))
FROM notification.notification;
