-- 회원 변경분 내부 API(#769)의 키셋 조회: (updated_at, id) 순으로 커서 뒤의 회원을 읽는다
CREATE INDEX idx_users_updated_at_id ON members.users (updated_at, id);
