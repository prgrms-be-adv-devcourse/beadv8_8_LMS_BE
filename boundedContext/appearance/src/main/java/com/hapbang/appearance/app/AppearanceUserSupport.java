package com.hapbang.appearance.app;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.AppearanceErrorCode;
import com.hapbang.appearance.domain.AppearanceException;
import com.hapbang.appearance.domain.AppearanceUser;
import com.hapbang.appearance.out.AppearanceUserRepository;

import lombok.RequiredArgsConstructor;

/**
 * 출연 회원 조회. 다른 UseCase가 호스트·게스트 자격을 확인할 때 쓴다. 트랜잭션은 호출한 Facade가 연다.
 */
@Service
@RequiredArgsConstructor
public class AppearanceUserSupport {

    private final AppearanceUserRepository appearanceUserRepository;

    /**
     * @throws AppearanceException 출연 회원이 없을 때({@code APPEARANCE_USER_NOT_FOUND})
     */
    public AppearanceUser getById(Long userId) {
        Optional<AppearanceUser> found = appearanceUserRepository.findById(userId);
        if (found.isEmpty()) {
            throw new AppearanceException(AppearanceErrorCode.APPEARANCE_USER_NOT_FOUND);
        }
        return found.get();
    }

    /**
     * 출연 거래에 참여할 회원을 찾고 자격(정상 상태, 인플루언서 이상)을 확인한다.
     * 세션 역할은 로그인 시점 값이라, 로그인 이후 정지·강등된 경우는 여기서 막힌다.
     *
     * @throws AppearanceException 회원이 없거나({@code APPEARANCE_USER_NOT_FOUND}), 자격이 없을 때
     */
    public AppearanceUser getParticipant(Long userId) {
        AppearanceUser user = getById(userId);
        user.validateCanAppear();
        return user;
    }

    /**
     * 출연 요청을 받을 게스트를 찾고 자격을 확인한다. 오류 코드는 {@link #getParticipant}와 같고, 메시지만 게스트 기준으로 바꾼다.
     *
     * @throws AppearanceException 게스트가 없거나({@code APPEARANCE_USER_NOT_FOUND}), 자격이 없을 때
     */
    public AppearanceUser getGuest(Long guestId) {
        try {
            return getParticipant(guestId);
        } catch (AppearanceException exception) {
            AppearanceErrorCode errorCode = exception.getErrorCode();
            String detail = switch (errorCode) {
                case APPEARANCE_USER_NOT_FOUND -> "게스트 회원 정보를 찾을 수 없습니다.";
                case APPEARANCE_USER_NOT_ACTIVE -> "게스트가 현재 출연 요청을 받을 수 없는 회원입니다.";
                case NOT_INFLUENCER -> "인플루언서 이상에게만 출연을 요청할 수 있습니다.";
                default -> exception.getMessage();
            };
            throw new AppearanceException(errorCode, detail);
        }
    }
}
