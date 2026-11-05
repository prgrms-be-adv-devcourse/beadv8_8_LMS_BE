-- 지급 실패 기록·격리 컬럼(#218, D54)을 지운다 (#268, D70 (5), D78).
-- #277이 이 기록을 PAYOUT 작업으로 옮긴 뒤(V202610301001) 앱은 이 컬럼을 읽거나 쓰지 않는다.
-- 운영에는 옮길 기록이 없었고(2026-11-04 확인 0건) 모든 값이 기본값이라 지워도 잃는 데이터가 없다.
alter table lectures.order_item
    drop column payout_failure_count,
    drop column last_payout_failure_code,
    drop column last_payout_failed_at,
    drop column payout_quarantined_at;
