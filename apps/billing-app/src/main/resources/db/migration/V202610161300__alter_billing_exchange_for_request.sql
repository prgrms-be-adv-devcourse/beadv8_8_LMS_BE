-- 수익 HP 환전 신청·테스트 계좌 스냅샷·수익 HP 보류·신청 취소 (#285). 기존 테이블은 #285 전까지 쓰지 않았다(행 없음).

-- 1) 환전 상태에 CANCELED(관리자 승인 전 회원 취소, 보류한 수익 HP 전부 반환)를 더한다.
--    회원 취소는 REQUESTED에서만 할 수 있다(애플리케이션 규칙). 승인 상태는 관리자 승인 Issue에서 더한다.
ALTER TABLE billing.exchange DROP CONSTRAINT ck_exchange_status;
ALTER TABLE billing.exchange ADD CONSTRAINT ck_exchange_status
    CHECK (status IN ('REQUESTED', 'PROCESSING', 'COMPLETED', 'FAILED', 'UNKNOWN', 'CANCELED'));

-- 2) 취소 시각. CANCELED이면 반드시 있고, 그 밖의 상태에는 없다.
ALTER TABLE billing.exchange ADD COLUMN canceled_at TIMESTAMPTZ;
ALTER TABLE billing.exchange ADD CONSTRAINT ck_exchange_5
    CHECK ((status = 'CANCELED') = (canceled_at IS NOT NULL));

-- payout_scheduled_at(NOT NULL, 기존 컬럼)에는 잠정 정산 예정 시각을 넣는다: 신청일(KST)이 1~9일이면 당월 10일, 10일 이후면
-- 다음 달 10일, 그 날이 토·일요일이면 다음 월요일의 00:00 KST. 공휴일을 반영하지 않아 실제 송금일을 보장하지 않는다.
-- 공휴일 보정·지급 대상 마감 기준은 정산 Scheduler Issue에서 최종 반영한다.
