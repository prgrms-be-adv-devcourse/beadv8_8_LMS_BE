-- B0 커밋 설정(N13, ND-170): B0 cluster의 auxiliary database에만 synchronous_commit을 건다. 값은 psql 변수 sync_commit
-- (b0.sh·n15.sh의 B0_SYNC_COMMIT, 기본 off)다. on은 N17-3 진단 회차에서만 쓴다(ND-217).
-- off는 커밋이 WAL 디스크 동기화를 기다리지 않게 해 호스트 디스크 fsync 변동을 측정에서 뺀다. 운영 manifest·앱 설정은 바꾸지 않는다.
-- 새 연결부터 적용된다(앱은 회차마다 replicas 0에서 새로 뜬다).
-- 역할별 설정은 database 설정보다 우선한다. 그래서 auxiliary database에 걸린 설정(역할별 포함)과 앱 역할(auxiliary)에 걸린 설정
-- (모든 database)을 모두 내보낸다. 스크립트는 "(모든 역할)@auxiliary=synchronous_commit=<값>" 한 줄만 있을 때 통과시킨다.
-- postgres 역할 전역 설정과 ALTER SYSTEM 값은 내보내지 않는다. 실제 값은 스크립트가 postgres·auxiliary 역할 새 연결의 show로 따로 확인한다.
alter database auxiliary set synchronous_commit = :'sync_commit';
select coalesce(r.rolname, '(모든 역할)') || '@' || coalesce(d.datname, '(모든 database)') || '='
       || array_to_string(s.setconfig, ',')
from pg_db_role_setting s
left join pg_database d on d.oid = s.setdatabase
left join pg_roles r on r.oid = s.setrole
where d.datname = 'auxiliary' or r.rolname = 'auxiliary'
order by 1;
