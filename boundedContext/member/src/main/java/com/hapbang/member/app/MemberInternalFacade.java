package com.hapbang.member.app;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.member.domain.User;
import com.hapbang.member.out.UserRepository;
import com.hapbang.shared.member.dto.MemberPageDto;

import lombok.RequiredArgsConstructor;

/** 다른 모듈이 내부 HTTP API로 부르는 회원 조회 (#303). 내부 API 키로 인증한 호출만 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('INTERNAL')")
public class MemberInternalFacade {

    private final UserRepository userRepository;

    /** 회원 ID 오름차순 키셋 목록. afterId가 없으면 처음부터, size+1개를 읽어 다음 페이지가 있는지 판단한다 */
    @Transactional(readOnly = true)
    public MemberPageDto findMembers(Long afterId, int size) {
        List<User> users = userRepository.findByIdGreaterThanOrderByIdAsc(
                afterId == null ? 0L : afterId, Limit.of(size + 1));
        boolean hasNext = users.size() > size;
        List<User> page = hasNext ? users.subList(0, size) : users;
        return new MemberPageDto(page.stream().map(User::toDto).toList(),
                hasNext ? page.getLast().getId() : null);
    }
}
