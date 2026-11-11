-- 알림 모듈 운영 설정 값(ND-095). 한 번 쓰면 바꾸지 않는 값을 둔다. 지금은 알림 시작 시각(alert.start-at, ISO-8601) 한 행이다.
-- 앱이 운영에 처음 떴을 때 INSERT … ON CONFLICT DO NOTHING으로 기록하고, 재배포·재시작에도 처음 값을 쓴다
CREATE TABLE notification.notification_runtime_setting
(
    setting_key   VARCHAR(100)                   NOT NULL,
    setting_value VARCHAR(255)                   NOT NULL,
    created_at    TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_runtime_setting PRIMARY KEY (setting_key)
);
