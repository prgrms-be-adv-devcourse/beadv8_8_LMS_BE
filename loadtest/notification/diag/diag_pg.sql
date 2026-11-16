-- B0 처리량 변동 진단(N13): 한 번에 한 시각으로 찍는다.
-- tick: 표본 시각 / act: 알림 database의 활동 중 연결(상태, 대기 종류·이름, 막는 pid, 쿼리 앞부분)
-- row: 행 잠금(tuple lock)이 걸린 회원 복제본 ID. 지갑·강의 소비자가 같은 받는 사람 행을 잠그는지 본다(ND-053)
with t as (select to_char(extract(epoch from clock_timestamp()), 'FM9999999999.000') as t)
select 'tick', t.t, '', '', '', '', '', '' from t
union all
select 'act', t.t, a.pid::text, coalesce(a.state, ''), coalesce(a.wait_event_type, ''), coalesce(a.wait_event, ''),
       pg_blocking_pids(a.pid)::text, left(regexp_replace(coalesce(a.query, ''), '\s+', ' ', 'g'), 80)
from pg_stat_activity a, t
where a.datname = 'auxiliary' and a.pid <> pg_backend_pid() and a.state is not null and a.state <> 'idle'
union all
select 'row', t.t, u.id::text, '', '', '', '', ''
from pg_locks l
join notification.notification_user u on u.ctid = format('(%s,%s)', l.page, l.tuple)::tid
cross join t
where l.locktype = 'tuple' and l.relation = 'notification.notification_user'::regclass;
