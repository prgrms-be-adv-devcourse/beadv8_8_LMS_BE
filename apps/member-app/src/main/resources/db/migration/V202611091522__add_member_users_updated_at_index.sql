-- 회원 변경분 내부 API(#769)의 키셋 조회: (updated_at, id) 순으로 커서 뒤의 회원을 읽는다.
-- updated_at이 null인 행은 커서로 이어 읽을 수 없어 변경분에서 빠지므로, 있으면 가입 시각(없으면 지금)으로 채운다.
-- 가입 때 감사 기능이 채우므로 보통은 바뀌는 행이 없다. version은 올리지 않는다(내용이 바뀐 것이 아니다)
UPDATE members.users SET updated_at = COALESCE(created_at, LOCALTIMESTAMP) WHERE updated_at IS NULL;

CREATE INDEX idx_users_updated_at_id ON members.users (updated_at, id);
