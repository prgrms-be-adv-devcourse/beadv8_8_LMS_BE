-- 팔로워 수를 치지직에서 마지막으로 조회한 시각 (#302). 기존 채널은 승인 요청 시각, 없으면 등록 시각으로 채운다
alter table members.influencer_channel
    add column follower_count_updated_at TIMESTAMP(6) WITHOUT TIME ZONE;

update members.influencer_channel
set follower_count_updated_at = coalesce(requested_at, created_at);

alter table members.influencer_channel
    alter column follower_count_updated_at set not null;
