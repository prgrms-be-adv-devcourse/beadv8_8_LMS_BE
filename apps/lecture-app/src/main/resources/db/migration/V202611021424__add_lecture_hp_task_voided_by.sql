-- 관리자가 결과를 확정하지 못한 주문 보류를 보류 무효화로 끝낼 때 그 관리자를 남긴다 (#535, D73). 시각은 completed_at이다
alter table lectures.lecture_hp_task
    add voided_by_user_id BIGINT;
