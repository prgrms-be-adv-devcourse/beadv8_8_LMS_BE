package com.hapbang.lecture.domain;

import com.hapbang.shared.member.domain.ReplicaUser;
import com.hapbang.shared.member.dto.UserDto;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 강의 모듈이 조회용으로 보관하는 회원 복제본. */
@Entity
@Table(name = "lecture_user", schema = "lectures")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LectureUser extends ReplicaUser {

    private LectureUser(UserDto user) {
        super(user);
    }

    public static LectureUser from(UserDto user) {
        return new LectureUser(user);
    }
}
