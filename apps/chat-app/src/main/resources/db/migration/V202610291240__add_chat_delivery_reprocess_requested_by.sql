-- 운영 재처리를 요청한 관리자(회원 ID). 관리자 API로 요청하면 남긴다. 그 밖의 재처리는 null이다.
alter table chats.chat_delivery_reprocess_audit
    add column requested_by BIGINT;

-- 관리자 격리 작업 목록: 격리한 체크포인트·적재 격리한 outbox만 키셋으로 훑는다
create index idx_chat_publish_checkpoint_dead on chats.chat_publish_checkpoint (consumer_group, id)
    where status = 'DEAD';
create index idx_chat_outbox_dead on chats.chat_outbox (id) where status = 'DEAD';
create index idx_chat_control_outbox_dead on chats.chat_control_outbox (id) where status = 'DEAD';
