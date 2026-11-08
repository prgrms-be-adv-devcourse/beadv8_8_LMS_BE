-- 조회 만료 배치가 만료 시각이 지난 읽기 전용 단체방을 찾는 부분 인덱스. 읽기 전용 방만 담으므로 배치가 EXPIRED로 바꾸면 빠진다.
create index idx_chat_room_group_history_expiry on chats.chat_room (history_expires_at)
    where room_type = 'GROUP' and status = 'READ_ONLY';

-- 초대 결과 기록 정리 배치가 보존 기간이 지난 행만 오래된 순으로 고르는 인덱스. 보존 중인 행을 묶음마다 다시 훑지 않게 한다.
create index idx_chat_group_invite_request_recorded_at on chats.chat_group_invite_request (recorded_at, id);
