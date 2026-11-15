-- B0 초기화: 알림 데이터 테이블만 비운다. notification_runtime_setting·Flyway 이력·다른 schema는 둔다(N13)
TRUNCATE notification.notification, notification.notification_user, notification.notification_payout_item,
         notification.notification_schedule;
