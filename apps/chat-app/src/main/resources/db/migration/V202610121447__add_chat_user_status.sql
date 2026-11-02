-- 채팅 회원을 shared ReplicaUser 상속으로 바꾸면서 회원 상태(ACTIVE/SUSPENDED/WITHDRAWN)를 복제한다.
-- 기존 행은 deleted_at으로 탈퇴 여부를 판단해 채운다. deleted_at 컬럼은 더 이상 쓰지 않지만 삭제는 팀 확인 후 별도로 한다.
alter table chats.chat_user
    add column status VARCHAR(255);

update chats.chat_user
set status = case when deleted_at is null then 'ACTIVE' else 'WITHDRAWN' end;

alter table chats.chat_user
    alter column status set not null;
