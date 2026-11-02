-- 출연 수익 조회 (#301): 게스트의 출연에 지급·위약금 작업을 붙여 읽는다 (hp_tasks.appearance_id로 조인).
-- 지금은 (ref_type, ref_id) 인덱스만 있어 출연 ID로 찾으면 작업 테이블을 모두 읽는다.
create index idx_hp_tasks_appearance_id on appearances.hp_tasks (appearance_id);
