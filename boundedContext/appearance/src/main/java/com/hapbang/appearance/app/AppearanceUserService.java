package com.hapbang.appearance.app;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceUser;
import com.hapbang.appearance.out.AppearanceUserRepository;
import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;

/**
 * 출연 회원(복제 회원) 관리.
 * <ul>
 *     <li>{@link #sync}: 회원 이벤트로 받은 정보를 출연 회원에 반영한다.</li>
 *     <li>{@link #getParticipant}: 호스트·게스트로 참여할 수 있는 회원인지 확인하고 돌려준다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AppearanceUserService {

    private final AppearanceUserRepository appearanceUserRepository;

    /**
     * 출연 회원이 없으면 만들고, 있으면 정보만 갱신한다.
     * 같은 이벤트를 다시 받아도(재처리 등) 회원이 중복으로 생기지 않는다.
     */
    @Transactional
    public AppearanceUser sync(UserDto user) {
        return appearanceUserRepository.findById(user.id())
                .map(existing -> {
                    existing.syncFrom(user);
                    return existing;
                })
                .orElseGet(() -> appearanceUserRepository.save(AppearanceUser.from(user)));
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
