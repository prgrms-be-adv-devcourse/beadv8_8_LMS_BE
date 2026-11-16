-- V7 예약 종류·상태별 행 수
SELECT kind, status, count(*) FROM notification.notification_schedule GROUP BY kind, status ORDER BY kind, status;
