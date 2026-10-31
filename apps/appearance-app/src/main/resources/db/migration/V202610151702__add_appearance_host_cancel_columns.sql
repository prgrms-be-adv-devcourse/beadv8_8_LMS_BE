-- 호스트 일방 취소·모집 전체 취소 (#239)

-- 위약금 정산(PENALTY) HP 작업: 보류에서 위약금은 받는 회원(게스트)에게 지급하고 나머지는 보류한 회원(호스트)에게 반환한다.
-- amount = 정산 기준액(출연료), payout_amount = 그중 위약금. 반환(RELEASE) 작업은 두 컬럼을 비워 둔다.
alter table appearances.hp_tasks
    add column receiver_id   BIGINT,
    add column payout_amount BIGINT;
alter table appearances.hp_tasks
    add constraint ck_hp_tasks_penalty check (
        (task_type <> 'PENALTY' and receiver_id is null and payout_amount is null)
            or (task_type = 'PENALTY' and receiver_id is not null and appearance_id is not null
            and payout_amount > 0 and payout_amount <= amount));

-- 일방 취소 사유 (선택). 모집 전체 취소면 모집글 취소 사유와 같다
alter table appearances.appearances
    add column host_cancel_reason VARCHAR(500);

-- 모집 전체 취소 사유 (선택)
alter table appearances.recruits
    add column cancel_reason VARCHAR(500);
