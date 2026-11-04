-- 닉네임 중복 막기 (#646). 대소문자를 구분하지 않고 비교한다(Tester = tester). 저장은 입력한 그대로다.
-- 1) 이미 겹친 닉네임을 정리한다. 먼저 가입한 회원(가장 작은 ID)은 그대로 두고, 나머지는 뒤에 숫자 4자리를 붙인다.
-- 2) 바꾼 회원을 기록한다. 앱이 시작할 때 이 기록을 보고 UserModifiedEvent를 한 번 보내 다른 앱 복제본을 맞춘다.
-- 3) lower(nickname) 유일 인덱스로 이후 중복(동시 가입 포함)을 막는다. 유일 제약(UNIQUE)은 식을 쓸 수 없어 인덱스로 만든다.
-- 탈퇴 회원도 포함한다(30일 동안 닉네임을 묶어 두어 복구 때 겹치지 않게 한다. 30일 뒤 마스킹 #594에서 풀어 준다).

-- 마이그레이션이 바꾼 닉네임 기록. notified_at이 비어 있으면 아직 다른 앱에 알리지 않은 것이다
create table members.nickname_dedup
(
    user_id      BIGINT                         not null,
    old_nickname VARCHAR(255)                   not null,
    new_nickname VARCHAR(255)                   not null,
    created_at   TIMESTAMP(6) WITHOUT TIME ZONE not null default localtimestamp,
    notified_at  TIMESTAMP(6) WITHOUT TIME ZONE,
    constraint pk_nickname_dedup primary key (user_id)
);

alter table members.nickname_dedup
    add constraint FK_NICKNAME_DEDUP_ON_USER foreign key (user_id) references members.users (id);

do
$$
    declare
        rec       record;
        base      text;
        candidate text;
        tries     int;
    begin
        -- 같은 lower(nickname)에 더 작은 ID가 있는 회원만 바꾼다. 커서는 시작 시점 데이터를 보므로 앞에서 바꾼 값에 흔들리지 않는다
        for rec in
            select u.id, u.nickname
            from members.users u
            where u.nickname is not null
              and exists (select 1
                          from members.users o
                          where lower(o.nickname) = lower(u.nickname)
                            and o.id < u.id)
            order by u.id
            loop
                -- 12자 이내: 앞 8자 + 숫자 4자리. 자른 끝의 공백은 지운다(Nickname.fromGoogleName과 같은 방식)
                base := rtrim(left(rec.nickname, 8));
                tries := 0;
                loop
                    tries := tries + 1;
                    if tries > 100 then
                        raise exception '닉네임 중복 정리 실패: user_id=%, 숫자 100번을 뽑아도 모두 겹칩니다', rec.id;
                    end if;
                    candidate := base || (1000 + floor(random() * 9000))::int;
                    exit when not exists (select 1
                                          from members.users
                                          where lower(nickname) = lower(candidate));
                end loop;

                insert into members.nickname_dedup (user_id, old_nickname, new_nickname)
                values (rec.id, rec.nickname, candidate);

                -- 버전을 올려 복제본이 새 닉네임을 옛 이벤트보다 나중 것으로 판단하게 한다 (#284)
                update members.users
                set nickname   = candidate,
                    version    = version + 1,
                    updated_at = localtimestamp
                where id = rec.id;
            end loop;
    end
$$;

create unique index uk_users_nickname_lower on members.users (lower(nickname));
