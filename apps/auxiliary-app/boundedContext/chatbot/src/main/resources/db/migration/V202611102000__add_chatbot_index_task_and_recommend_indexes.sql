-- 색인 작업 실행기(#718 공통 기반)와 추천 쿼리용 인덱스(#718 강의 추천, #719 인플루언서 추천)

-- 마지막 실패 원인(예외 종류와 메시지 앞부분). 성공하면 비운다
ALTER TABLE chatbot.index_task ADD COLUMN last_error VARCHAR(500);

-- 같은 문서의 대기 중 요청을 찾는다(한 문서에 쌓인 요청을 한 번에 끝낸다)
CREATE INDEX idx_index_task_pending_doc ON chatbot.index_task (index_type, doc_id) WHERE done_at IS NULL;

-- 끝난 요청을 보관 기간 뒤 지운다
CREATE INDEX idx_index_task_done_at ON chatbot.index_task (done_at) WHERE done_at IS NOT NULL;

-- 강의 추천: 강의별 구매 수(인기순)
CREATE INDEX idx_lecture_purchase_copy_lecture ON chatbot.lecture_purchase_copy (lecture_id);

-- 강의 추천: 본인 강의 제외, 판매자별 강의
CREATE INDEX idx_lecture_copy_seller ON chatbot.lecture_copy (seller_user_id);

-- 인플루언서 추천: 회원별 출연 이력(호스트·게스트)
CREATE INDEX idx_appearance_copy_host ON chatbot.appearance_copy (host_id);
CREATE INDEX idx_appearance_copy_guest ON chatbot.appearance_copy (guest_id);
