-- 채널 심사 회차 (#874, ND-045). 심사가 시작된 횟수다: 등록하며 자동 승인되면 1, 승인 요청마다 1씩 는다(등록만 되면 0).
-- 채널 승인·반려 이벤트가 이 값을 담고, 알림은 <채널 ID>:<회차>로 사건을 구분한다
alter table members.influencer_channel
    add column review_count INTEGER default 0 not null;

-- 이미 있는 채널: 등록만 된 채널은 0, 한 번이라도 심사가 시작된 채널(심사 대기·반려·승인)은 1.
-- 지난 요청 횟수는 기록이 없어 최소값 1로 둔다. 배포 전 심사 결과는 이벤트로 나간 적이 없어 사건 ID가 겹치지 않는다
update members.influencer_channel
set review_count = 1
where verification_status <> 'REGISTERED';
