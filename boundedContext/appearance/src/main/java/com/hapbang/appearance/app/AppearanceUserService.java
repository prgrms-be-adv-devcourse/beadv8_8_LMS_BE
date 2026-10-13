package com.hapbang.appearance.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceUser;
import com.hapbang.appearance.out.AppearanceUserRepository;
import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 출연 회원(복제 회원) 관리.
 * <ul>
 *     <li>{@link #createIfAbsent}: 가입 정보로 출연 회원을 만든다. 이미 있으면 무시한다.</li>
 *     <li>{@link #update}: 변경 정보로 있는 출연 회원만 갱신한다.</li>
 *     <li>{@link #getParticipant}: 호스트·게스트로 참여할 수 있는 회원인지 확인하고 돌려준다.</li>
 * </ul>
 * 출연 회원을 만드는 곳은 가입 하나뿐이라, 늦게 처리된 가입 정보가 이미 반영된 변경 정보를 덮어쓰지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppearanceUserService {

    private final AppearanceUserRepository appearanceUserRepository;

    /**
     * 출연 회원이 없으면 만든다. 이미 있으면(같은 가입 이벤트의 재처리 등) 아무것도 바꾸지 않는다.
     */
    @Transactional
    public void createIfAbsent(UserDto user) {
        if (appearanceUserRepository.existsById(user.id())) {
            log.debug("이미 복제된 출연 회원이라 가입 정보를 무시한다 userId={}", user.id());
            return;
        }
        appearanceUserRepository.save(AppearanceUser.from(user));
    }

    /**
     * 있는 출연 회원의 닉네임·역할·상태를 갱신한다.
     *
     * @throws AppearanceException 출연 회원이 아직 없을 때({@code APPEARANCE_USER_NOT_FOUND}).
     *                             가입 이벤트가 처리되기 전이므로 실패로 남기고, 재처리 때 반영한다.
     */
    @Transactional
    public void update(UserDto user) {
        AppearanceUser appearanceUser = appearanceUserRepository.findById(user.id())
                .orElseThrow(() -> new AppearanceException(AppearanceErrorCode.APPEARANCE_USER_NOT_FOUND));
        appearanceUser.syncFrom(user);
    }

    /**
     * 출연 거래에 참여할 회원을 찾고 자격을 확인한다.
     *
     * @throws AppearanceException 회원이 없거나({@code APPEARANCE_USER_NOT_FOUND}), 자격이 없을 때
     */
    @Transactional(readOnly = true)
    public AppearanceUser getParticipant(Long userId) {
        AppearanceUser user = appearanceUserRepository.findById(userId)
                .orElseThrow(() -> new AppearanceException(AppearanceErrorCode.APPEARANCE_USER_NOT_FOUND));
        user.validateCanAppear();
        return user;
    }
}
