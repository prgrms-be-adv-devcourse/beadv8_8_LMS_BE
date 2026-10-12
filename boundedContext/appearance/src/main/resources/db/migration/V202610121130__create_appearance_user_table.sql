CREATE SCHEMA IF NOT EXISTS appearances;

-- 출연 모듈에 복제한 회원 (회원 모듈 이벤트로 생성·갱신). id는 원본 회원 ID를 그대로 쓴다.
create table appearances.users
(
    id         BIGINT                         not null,
    nickname   VARCHAR(255),
    role       VARCHAR(255),
    status     VARCHAR(255),
    created_at TIMESTAMP(6) WITHOUT TIME ZONE,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE,
    constraint pk_appearance_users primary key (id)
);
