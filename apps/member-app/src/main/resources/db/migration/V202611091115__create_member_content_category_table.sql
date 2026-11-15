-- 콘텐츠 분야 목록 (#711). 인플루언서가 프로필에 0~5개 고르는 주제다. 코드는 한 번 정하면 바꾸지 않고, 이름은 바꿀 수 있다.
-- active를 끄면 분야 목록 API·검색 필터 후보에서만 빠진다. 이미 고른 회원의 값은 지우지 않는다(Q50).
-- 첫 버전은 추가·끄기 API가 없고 마이그레이션 SQL로 한다
create table members.content_category
(
    code   VARCHAR(50)          not null,
    name   VARCHAR(50)          not null,
    active BOOLEAN default true not null,
    constraint pk_content_category primary key (code),
    constraint uc_content_category_name unique (name)
);
