-- 강의 내용 다름 이의 제기 문의의 대상과 관리자 판단 (#189).
-- 대상은 다른 모듈(강의)의 ID만 담는다(FK를 걸지 않는다). 다른 유형의 문의는 모두 null이다.
alter table customersupport.inquiry
    add column target_type          VARCHAR(255),
    add column target_id            BIGINT,
    add column objection_result     VARCHAR(255),
    add column objection_resolved_by BIGINT,
    add column objection_resolved_at TIMESTAMP(6) WITHOUT TIME ZONE;
