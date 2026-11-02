-- 로그아웃 연결 종료(LOGOUT_USER)가 닫을 연결을 가리는 기준 시각. 회원 앱 로그아웃 이벤트의 loggedOutAt(처리 시각을 넘지 않게
-- 줄인 값)이다. 이 시각보다 먼저 인증한 연결만 닫는다. 재검사(RECHECK_USER) 행과 이 컬럼이 생기기 전의 로그아웃 행은 null이고,
-- null인 로그아웃 행은 예전처럼 그 회원의 연결을 모두 닫는다.
alter table chats.chat_control_outbox
    add column logged_out_at TIMESTAMP(6) WITH TIME ZONE;
