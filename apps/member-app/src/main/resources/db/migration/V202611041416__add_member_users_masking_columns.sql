-- 탈퇴 30일 뒤 개인정보 마스킹 (#594).
-- masked_at: 마스킹한 시각. 값이 있으면 이미 처리한 회원이라 배치가 다시 고르지 않는다.
-- restore_failed_at: 복구 기한 안에 복구를 눌렀는데 지갑을 다시 열지 못해(503 RESTORE_UNAVAILABLE) 실패한 마지막 시각.
--   값이 기한 안이면 복구 기한과 마스킹을 7일 미룬다(회원 잘못이 아닌 장애로 기한을 놓치지 않게). 복구하거나 다시 탈퇴하면 지운다
alter table members.users
    add column masked_at TIMESTAMP(6) WITHOUT TIME ZONE,
    add column restore_failed_at TIMESTAMP(6) WITHOUT TIME ZONE;

-- 마스킹 배치 대상 찾기: 탈퇴했고 아직 마스킹하지 않은 회원을 탈퇴 시각 순으로 읽는다
create index ix_users_masking_target on members.users (deleted_at)
    where status = 'WITHDRAWN' and masked_at is null;
