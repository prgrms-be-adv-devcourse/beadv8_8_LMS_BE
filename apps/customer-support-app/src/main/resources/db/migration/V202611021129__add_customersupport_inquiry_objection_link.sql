-- 강의 내용 다름 이의 제기 문의의 강의 연동 상태 (#279, D60·D71). 접수는 강의 내부 HTTP, 판단·철회는 이벤트로 보내고
-- 강의의 결과 이벤트로 끝나므로, 그 사이 상태와 지금 보낸 명령(ID·내용 지문), 거절 사유, HP 반환 상태를 문의에 둔다.
-- 다른 유형의 문의는 모두 null이다. #433 이전의 이의 제기 문의는 운영 DB에 없어 채우지 않는다(D71).
alter table customersupport.inquiry
    add column objection_link_status   VARCHAR(30),
    add column objection_command_id    VARCHAR(100),
    add column objection_command_hash  VARCHAR(64),
    add column objection_failure_code  VARCHAR(50),
    add column objection_requested_by  BIGINT,
    add column objection_pending_reply TEXT,
    add column hp_return_status        VARCHAR(20);

-- 접수 재시도 배치가 접수 처리 중인 문의를 찾는다
create index idx_inquiry_objection_link_status on customersupport.inquiry (objection_link_status);
