package com.hapbang.lecture.out;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.lecture.domain.FileResource;

public interface FileResourceRepository extends JpaRepository<FileResource, Long> {
}
