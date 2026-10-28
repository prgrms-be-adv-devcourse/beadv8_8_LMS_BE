-- 회원 동시 수정 방지용 낙관적 락 버전 (#284). 기존 회원은 0부터 시작하고 이후 JPA가 수정마다 1씩 올린다
alter table members.users
    add column version BIGINT not null default 0;
