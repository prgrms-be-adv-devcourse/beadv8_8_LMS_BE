-- AI 추천용 동기화(#712, D82)의 버전. 장바구니 항목·리뷰가 바뀌면 오르고, 이벤트와 동기화 내부 API에 실린다.
-- 기존 행은 0에서 시작한다. 받는 쪽은 처음 받은 행을 그대로 넣으므로 0이어도 된다.
alter table lectures.cart_item
    add column version bigint not null default 0;

alter table lectures.lecture_review
    add column version bigint not null default 0;
