-- 내용 다름 이의 제기(#156): 접수한 고객센터 문의 ID(판단할 때 대조)와 판단한 관리자 ID.
-- 다른 모듈(고객센터·회원)의 ID라 FK를 두지 않는다
alter table lectures.order_item
    add column objection_inquiry_id          BIGINT,
    add column objection_resolved_by_user_id BIGINT;
