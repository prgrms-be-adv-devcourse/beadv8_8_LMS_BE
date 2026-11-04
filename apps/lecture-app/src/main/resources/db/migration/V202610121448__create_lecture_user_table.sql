create table lectures.lecture_user
(
    id         BIGINT       not null,
    nickname   VARCHAR(255),
    role       VARCHAR(255),
    status     VARCHAR(255),
    created_at TIMESTAMP(6) WITHOUT TIME ZONE,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE,
    constraint pk_lecture_user primary key (id)
);
