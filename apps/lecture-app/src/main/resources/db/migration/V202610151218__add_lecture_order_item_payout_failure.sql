-- 지급 실패 기록과 격리(#218, D54). 지급 실패는 지급 트랜잭션과 함께 롤백되므로 배치가 새 트랜잭션으로 남긴다.
-- 재시도로 풀리지 않는 실패는 격리 시각을 남겨 지급 배치 대상에서 빼고, 관리자가 해제하면 비운다
alter table lectures.order_item
    add column payout_failure_count     INTEGER                        not null default 0,
    add column last_payout_failure_code VARCHAR(100),
    add column last_payout_failed_at    TIMESTAMP(6) WITHOUT TIME ZONE,
    add column payout_quarantined_at    TIMESTAMP(6) WITHOUT TIME ZONE;
