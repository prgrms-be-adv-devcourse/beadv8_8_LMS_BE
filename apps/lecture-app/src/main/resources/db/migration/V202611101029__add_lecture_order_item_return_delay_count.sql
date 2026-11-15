-- 반환 지연 회차(#818, D83). 이의 제기 인정 반환 작업이 멈출(DEAD) 때마다 1 오르고, 알림이 쓰는 반환 지연 이벤트에 실린다.
-- 기존 행은 0에서 시작한다(값을 채우는 UPDATE가 필요 없다). 배포 전에 이미 멈춘 반환은 다음에 멈출 때 회차 1이 된다
alter table lectures.order_item
    add column return_delay_count INTEGER default 0 not null;
