-- 회원 이벤트 순서 처리 (#284): 마지막으로 반영한 회원 원본 버전(null = 모름)과 복제본 행의 낙관적 락 버전
alter table chats.chat_user
    add column member_version BIGINT,
    add column version        BIGINT not null default 0;
