-- 로그아웃 연결 종료(LOGOUT_USER)는 원본 버전이 없고 같은 회원이 여러 번 로그아웃하므로 이벤트마다 한 행을 남긴다.
-- (회원, 원본 버전, 종류) 중복 식별은 재검사(RECHECK_USER)에만 둔다. 재검사 기록의 ON CONFLICT는 이 인덱스의 조건을 함께 적는다.
alter table chats.chat_control_outbox
    drop constraint uk_chat_control_outbox_user_version_type;

create unique index uk_chat_control_outbox_user_version_type
    on chats.chat_control_outbox (user_id, member_version, control_type) nulls not distinct
    where control_type = 'RECHECK_USER';
