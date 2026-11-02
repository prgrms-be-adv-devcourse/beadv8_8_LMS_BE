package com.hapbang.lecture.out;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.lecture.domain.LectureObjectionCommand;

/** 이의 제기 명령 이력 (#278). 행을 바꾸거나 지우지 않는다 */
public interface LectureObjectionCommandRepository extends JpaRepository<LectureObjectionCommand, Long> {

    Optional<LectureObjectionCommand> findByCommandId(String commandId);
}
