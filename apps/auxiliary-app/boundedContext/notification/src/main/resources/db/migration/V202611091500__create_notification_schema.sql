-- 알림 모듈 schema(ND-007). auxiliary-app은 database 하나를 모듈이 schema로 나눠 쓴다(apps/auxiliary-app/README.md).
-- 이 모듈의 테이블은 모두 notification schema에 만든다. Flyway 기본 schema는 auxiliary라 테이블 이름에 schema를 붙인다.
CREATE SCHEMA IF NOT EXISTS notification;

-- 알림 회원 복제본(ND-018, ND-025). 회원 이벤트(member.events.v1)의 가입으로 만들고 변경으로 갱신한다. 받는 사람의 탈퇴 여부·역할·닉네임을
-- 여기서 읽는다. id는 회원 ID를 그대로 쓰고 회원 테이블과 FK·JOIN하지 않는다.
-- 컬럼은 shared ReplicaUser(BaseUser·BaseEntity)와 다른 모듈 복제본(chats.chat_user, billing.billing_user)에 맞춘다.
-- 나머지 테이블(notification, notification_payout_item, notification_schedule)은 처음 쓰는 작업에서 만든다(ND-046).
CREATE TABLE notification.notification_user
(
    id             BIGINT                         NOT NULL,
    nickname       VARCHAR(255)                   NOT NULL,
    role           VARCHAR(255)                   NOT NULL,
    status         VARCHAR(255)                   NOT NULL,
    -- 마지막으로 반영한 회원 원본 버전(UserDto.version). 이보다 크지 않은 정보는 무시한다(null: 아직 버전 있는 정보를 받은 적 없음)
    member_version BIGINT,
    -- 복제본 행의 낙관적 락
    version        BIGINT                         NOT NULL DEFAULT 0,
    created_at     TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at     TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_user PRIMARY KEY (id)
);
