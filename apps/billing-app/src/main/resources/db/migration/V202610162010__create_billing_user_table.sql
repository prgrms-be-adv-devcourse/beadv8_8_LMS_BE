-- Billing의 회원 복제본 (#294). 회원 모듈의 가입·변경 이벤트(UserJoinedEvent·UserModifiedEvent)로만 채운다.
-- 관리자 환전 승인 때 신청자의 현재 역할·상태를 확인하는 데 쓴다. 회원 테이블(members.users)과 FK·JOIN 없음.
-- 컬럼은 shared ReplicaUser(BaseUser·BaseEntity)와 다른 모듈 복제본(lectures.lecture_user 등)에 맞춘다:
-- 생성·수정 시각은 BaseEntity의 LocalDateTime이라 TIMESTAMP(6) WITHOUT TIME ZONE이다(Billing 금융 테이블의 TIMESTAMPTZ 규칙 예외).
-- 기존 회원은 채우지 않는다(Backfill 제외, 후속 Issue). 복제본이 없는 신청자는 관리자가 승인할 수 없다.
CREATE TABLE billing.billing_user (
    id             BIGINT       NOT NULL,
    nickname       VARCHAR(255),
    role           VARCHAR(255),
    status         VARCHAR(255),
    -- 마지막으로 반영한 회원 원본 버전(UserDto.version). Billing은 버전 있는 정보로만 만든다
    member_version BIGINT,
    -- 복제본 행의 낙관적 락
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMP(6) WITHOUT TIME ZONE,
    updated_at     TIMESTAMP(6) WITHOUT TIME ZONE,
    CONSTRAINT pk_billing_user PRIMARY KEY (id),
    CONSTRAINT ck_billing_user_role CHECK (role IN ('USER', 'INFLUENCER', 'VERIFIED_INFLUENCER', 'ADMIN')),
    CONSTRAINT ck_billing_user_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'WITHDRAWN'))
);
