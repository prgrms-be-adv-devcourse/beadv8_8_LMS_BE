-- 출연 확인 (#282)

-- 불참: 호스트 부정 응답(게스트가 출연하지 않음). AGENTS.md의 "노쇼"를 코드·API에서는 불참(ABSENT)으로 쓴다 (ISSUE.md APR-35 E1).
-- 아직 이 상태가 된 출연이 없어 값 변환은 필요 없다.
alter table appearances.appearances
    rename column no_show_at to absent_at;
alter table appearances.appearances
    add column absent_reason VARCHAR(500);

-- 이의 제기는 고객센터 관할이라 출연은 기록하지 않는다 (APR-35 E4). 쓰인 적 없는 컬럼이다.
alter table appearances.appearances
    drop column disputed_at;

-- 자동 확인 배치: status = 'ESTABLISHED' and scheduled_at <= 현재 - 확인 기간(7일)
create index idx_appearances_status_scheduled_at on appearances.appearances (status, scheduled_at);

-- 지급(PAYOUT) HP 작업: 보류에서 출연료(amount) 전부를 받는 회원(게스트)에게 지급한다 (payout_amount = amount).
-- 위약금 정산(PENALTY)은 그대로, 반환(RELEASE)은 받는 회원·지급액을 비워 둔다.
alter table appearances.hp_tasks
    drop constraint ck_hp_tasks_penalty;
alter table appearances.hp_tasks
    add constraint ck_hp_tasks_receiver check (
        (task_type = 'RELEASE' and receiver_id is null and payout_amount is null)
            or (task_type = 'PENALTY' and receiver_id is not null and appearance_id is not null
            and payout_amount > 0 and payout_amount <= amount)
            or (task_type = 'PAYOUT' and receiver_id is not null and appearance_id is not null
            and payout_amount = amount));
