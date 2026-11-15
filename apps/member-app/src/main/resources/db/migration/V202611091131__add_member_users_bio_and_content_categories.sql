-- 자기소개와 콘텐츠 분야 (#711). 인플루언서만 설정하고, 강등·탈퇴해도 지우지 않으며 마스킹(#594) 때만 지운다.
-- bio: 앞뒤 공백을 지운 100자 이내, 없으면 null
alter table members.users
    add column bio VARCHAR(100);

-- 회원이 고른 콘텐츠 분야 0~5개. sort_order는 고른 순서(0부터).
-- 바꿀 때는 그 회원의 행을 모두 지우고 다시 넣으므로 같은 회원·같은 분야는 한 줄만 둔다.
-- code는 분야 목록을 가리킨다(목록에서 끈 분야도 행은 남아 있어 FK가 깨지지 않는다)
create table members.user_content_category
(
    user_id    BIGINT      not null,
    sort_order INTEGER     not null,
    code       VARCHAR(50) not null,
    constraint pk_user_content_category primary key (user_id, sort_order),
    constraint uk_user_content_category_code unique (user_id, code)
);

alter table members.user_content_category
    add constraint FK_USER_CONTENT_CATEGORY_ON_USER foreign key (user_id) references members.users (id);

alter table members.user_content_category
    add constraint FK_USER_CONTENT_CATEGORY_ON_CODE foreign key (code) references members.content_category (code);

-- 분야 목록 API가 분야별로 고른 회원 수를 셀 때 code로 찾는다
create index ix_user_content_category_code on members.user_content_category (code);
