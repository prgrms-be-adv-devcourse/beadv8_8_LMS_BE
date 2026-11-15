-- 검수 회차(#872, D85). 등록이 1회차이고 재검수를 신청할 때마다 1 오르며, 알림이 쓰는 검수 승인·반려 이벤트에 실린다.
-- 기존 행은 1에서 시작한다. 이 배포 전에는 승인·반려 이벤트가 나간 적이 없어, 기존 강의의 다음 검수 결과가 1회차여도 겹치는 회차가 없다
alter table lectures.lecture
    add column inspection_count INTEGER default 1 not null;
