package com.hapbang.member.out;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hapbang.member.domain.NicknameDedup;

/** 마이그레이션이 바꾼 중복 닉네임 기록 저장소 (#646) */
public interface NicknameDedupRepository extends JpaRepository<NicknameDedup, Long> {

    /**
     * 아직 알리지 않은 기록을 잠가 읽는다. 앱 여러 대가 동시에 시작해도 다른 쪽이 잠근 행은 건너뛰어
     * 같은 회원 이벤트를 두 번 보내지 않는다(SKIP LOCKED)
     */
    @Query(value = """
            select * from members.nickname_dedup
            where notified_at is null
            order by user_id
            for update skip locked
            """, nativeQuery = true)
    List<NicknameDedup> findPendingForUpdate();
}
