-- 콘텐츠 분야 처음 목록 23개 (#711, Q9·Q45). 넓은 것(게임)과 좁은 것(메이플스토리)이 위아래 없이 나란히 있다.
-- 버추얼은 주제가 아니라 뺐고, ASMR은 처음 목록에 넣지 않는다.
-- 회원 앱 테스트는 DB를 비울 때마다 이 파일을 다시 실행한다(hapbang-module-test.yaml의 sql-load-script-source)
insert into members.content_category (code, name) values ('GAME', '게임');
insert into members.content_category (code, name) values ('MUKBANG', '먹방');
insert into members.content_category (code, name) values ('BEAUTY', '뷰티');
insert into members.content_category (code, name) values ('JUST_CHATTING', '저스트채팅');
insert into members.content_category (code, name) values ('MUSIC', '음악');
insert into members.content_category (code, name) values ('SPORTS', '스포츠');
insert into members.content_category (code, name) values ('EDUCATION', '교육·지식');
insert into members.content_category (code, name) values ('DAILY_LIFE', '일상');
insert into members.content_category (code, name) values ('LEAGUE_OF_LEGENDS', '리그 오브 레전드');
insert into members.content_category (code, name) values ('VALORANT', '발로란트');
insert into members.content_category (code, name) values ('MAPLESTORY', '메이플스토리');
insert into members.content_category (code, name) values ('BATTLEGROUNDS', '배틀그라운드');
insert into members.content_category (code, name) values ('LOST_ARK', '로스트아크');
insert into members.content_category (code, name) values ('OVERWATCH', '오버워치');
insert into members.content_category (code, name) values ('MINECRAFT', '마인크래프트');
insert into members.content_category (code, name) values ('FC_ONLINE', 'FC 온라인');
insert into members.content_category (code, name) values ('STARCRAFT', '스타크래프트');
insert into members.content_category (code, name) values ('DUNGEON_FIGHTER', '던전앤파이터');
insert into members.content_category (code, name) values ('SINGING', '노래');
insert into members.content_category (code, name) values ('INSTRUMENT', '악기 연주');
insert into members.content_category (code, name) values ('COOKING', '요리');
insert into members.content_category (code, name) values ('TRAVEL', '여행');
insert into members.content_category (code, name) values ('DRAWING', '그림');
