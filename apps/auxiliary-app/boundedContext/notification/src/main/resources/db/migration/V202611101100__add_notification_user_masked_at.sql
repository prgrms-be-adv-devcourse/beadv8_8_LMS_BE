-- 파기 표시(ND-054). UserMaskedEvent를 받은 시각이다. 이 표시가 있는 회원은 알림에 닉네임이 `탈퇴회원#{회원ID}`로 저장된다.
ALTER TABLE notification.notification_user
    ADD COLUMN masked_at TIMESTAMP(6) WITH TIME ZONE;

-- 복제본이 없는 회원의 파기는 파기 상태 복제본으로 만든다. UserMaskedEvent에는 역할이 없어 역할을 비울 수 있게 한다(ND-054)
ALTER TABLE notification.notification_user
    ALTER COLUMN role DROP NOT NULL;
