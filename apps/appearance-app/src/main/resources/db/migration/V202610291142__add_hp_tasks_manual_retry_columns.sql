-- 관리자 다시 시도 (#397): 격리된(DEAD) HP 작업을 관리자가 같은 멱등 키로 다시 실행한 기록.
-- 다시 시도하면 시도 횟수(attempt_count)를 0으로 되돌려 재시도 규칙을 새로 적용하므로, 다시 시도 이력은 아래 컬럼에 따로 남긴다.
alter table appearances.hp_tasks
    add column manual_retry_count    INTEGER not null default 0,
    add column last_manual_retry_by  BIGINT,
    add column last_manual_retry_at  TIMESTAMP(6) WITHOUT TIME ZONE;
alter table appearances.hp_tasks
    add constraint ck_hp_tasks_manual_retry check (
        (manual_retry_count = 0 and last_manual_retry_by is null and last_manual_retry_at is null)
            or (manual_retry_count > 0 and last_manual_retry_by is not null and last_manual_retry_at is not null));
