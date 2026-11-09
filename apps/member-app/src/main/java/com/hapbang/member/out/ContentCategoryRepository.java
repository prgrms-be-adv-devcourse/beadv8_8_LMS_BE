package com.hapbang.member.out;

import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.member.domain.ContentCategory;

/** 콘텐츠 분야 목록 저장소 (#711) */
public interface ContentCategoryRepository extends JpaRepository<ContentCategory, String> {

    /** 주어진 코드 중 목록에 있고 새로 고를 수 있는(active) 분야 수. 코드에 중복이 없을 때 코드 수와 같으면 모두 고를 수 있다 */
    long countByCodeInAndActiveTrue(Collection<String> codes);
}
