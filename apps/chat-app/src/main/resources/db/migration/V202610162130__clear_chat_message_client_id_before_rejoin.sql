-- 재입장 이전 참여에서 보낸 메시지의 전송 ID를 비운다 (#311).
-- 재전송 방지(#261)는 같은 참여 안에서만 적용한다. 앞으로는 재입장할 때 비우고, 이미 재입장한 회원의 옛 메시지는 여기서 비운다.
-- 비우지 않으면 재입장 뒤 같은 ID로 새로 저장할 때 (방, 보낸 사람, 전송 ID) 유일 인덱스에 걸린다.
update chats.chat_message m
set client_message_id = null
from chats.chat_room_member cm
where cm.chat_room_id = m.chat_room_id
  and cm.user_id = m.sender_user_id
  and m.client_message_id is not null
  and m.room_sequence < cm.visible_from_sequence;
