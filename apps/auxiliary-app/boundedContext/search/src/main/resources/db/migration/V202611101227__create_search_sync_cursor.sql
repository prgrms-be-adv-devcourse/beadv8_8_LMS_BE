-- 증분 보정 커서(#772). 회원 변경분 내부 API(#769)를 어디까지 읽었는지 적는다. 읽는 대상마다 한 행이다.
-- 커서 시각은 회원 앱이 준 값 그대로다(회원 앱 시간대 Asia/Seoul, 오프셋 없음, 마이크로초).
CREATE TABLE search.sync_cursor (
    -- 읽는 대상(member-changes)
    name              VARCHAR(50)  PRIMARY KEY,
    -- 마지막으로 반영한 회원의 updated_at. 다음 회차는 여기서 1분 뺀 시각부터 다시 읽는다
    member_updated_at TIMESTAMP(6) NOT NULL,
    -- 마지막으로 반영한 회원 ID. 같은 시각에 바뀐 회원을 가른다
    member_id         BIGINT       NOT NULL,
    -- 마지막으로 끝(hasNext=false)까지 읽은 시각(KST). 커서 지연 지표가 쓴다. 아직 끝까지 읽은 적이 없으면 null
    caught_up_at      TIMESTAMP(6)
);
