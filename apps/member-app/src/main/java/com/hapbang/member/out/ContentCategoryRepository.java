package com.hapbang.member.out;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.member.domain.ContentCategory;

/** 콘텐츠 분야 목록 저장소 (#711) */
public interface ContentCategoryRepository extends JpaRepository<ContentCategory, String> {
}
