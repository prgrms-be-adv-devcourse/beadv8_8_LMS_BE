-- 모집 기한 마감 배치(#222): 모집 중이고 마감 일시가 지난 모집글을 마감 일시가 이른 순으로 찾는다
create index idx_appearance_recruits_status_recruit_deadline on appearances.recruits (status, recruit_deadline);
