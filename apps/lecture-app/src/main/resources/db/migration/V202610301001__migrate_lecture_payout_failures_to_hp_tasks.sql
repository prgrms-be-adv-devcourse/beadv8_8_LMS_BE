-- 지급 실패 기록(D54)을 PAYOUT 작업으로 옮긴다 (#277, D59 (7), D70 (4)).
-- 대상: 구매 확정이고 지급 실패 기록이 있는 항목. 항목은 지급 처리 중이 되고, 격리됐으면 작업은 DEAD, 아니면 PENDING이다.
-- 누적 시도 횟수 = 기존 실패 횟수, 자동 재시도 횟수 = 0 (관리자 재시도와 같은 규칙, D57 (9)).
-- 요청 값 지문은 LectureHpTask.fingerprint와 같은 문자열(목적|요청 ID|주문|항목|구매자|판매자|금액)의 SHA-256이다.
-- 다시 실행해도 결과가 같다: 이미 옮긴 항목은 지급 처리 중이라 대상에서 빠지고, 같은 요청 ID의 작업은 만들지 않는다.
-- 시각 컬럼은 앱처럼 한국 시간(LocalDateTime)으로 쓴다. 항목의 실패 기록 컬럼은 이력으로 남기고 #268에서 지운다.

insert into lectures.lecture_hp_task (task_type, purpose, request_id, order_id, order_item_id, payer_user_id,
                                      receiver_user_id, amount_hp, payload_fingerprint, status, attempt_count,
                                      total_attempt_count, manual_retry_count, next_attempt_at, last_error_code,
                                      last_failed_at, created_at, updated_at, version)
select 'PAYOUT',
       'PAYOUT',
       'LECTURE-PAYOUT-' || i.id,
       i.order_id,
       i.id,
       i.buyer_user_id,
       i.seller_user_id,
       i.price_hp,
       encode(sha256(convert_to('PAYOUT|LECTURE-PAYOUT-' || i.id || '|' || i.order_id || '|' || i.id || '|'
                                    || i.buyer_user_id || '|' || i.seller_user_id || '|' || i.price_hp, 'UTF8')),
              'hex'),
       case when i.payout_quarantined_at is not null then 'DEAD' else 'PENDING' end,
       0,
       i.payout_failure_count,
       0,
       now() at time zone 'Asia/Seoul',
       i.last_payout_failure_code,
       i.last_payout_failed_at,
       now() at time zone 'Asia/Seoul',
       now() at time zone 'Asia/Seoul',
       0
from lectures.order_item i
where i.status = 'CONFIRMED'
  and i.payout_failure_count > 0
on conflict (request_id) do nothing;

update lectures.order_item i
set status  = 'PAYOUT_PENDING',
    version = coalesce(i.version, 0) + 1
where i.status = 'CONFIRMED'
  and i.payout_failure_count > 0
  and exists (select 1 from lectures.lecture_hp_task t where t.request_id = 'LECTURE-PAYOUT-' || i.id);
