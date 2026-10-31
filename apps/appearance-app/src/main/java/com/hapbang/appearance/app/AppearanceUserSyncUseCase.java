package com.hapbang.appearance.app;

import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.hapbang.appearance.domain.AppearanceUser;
import com.hapbang.appearance.out.AppearanceUserRepository;
import com.hapbang.shared.member.dto.UserDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원 정보로 출연 회원(복제 회원)을 동기화한다. 트랜잭션은 호출한 {@link AppearanceUserFacade}가 연다.
 * <ul>
 *     <li>출연 회원은 가입 정보로만 만든다. 이미 있으면 무시한다(같은 가입 이벤트의 재처리).</li>
 *     <li>변경 정보는 있는 출연 회원만 갱신한다. 없으면(가입 이벤트가 아직 처리되지 않음) 예외를 던져 처리 실패로 남기고,
 *     재처리 때 반영한다.</li>
 *     <li>관리자 Backfill(#394)은 회원 원본의 현재 정보(스냅샷)로 없으면 만들고 있으면 버전 비교로 갱신한다.
 *     회원 원본 버전이 함께 오므로, 이벤트로 반영한 최신 정보를 옛 스냅샷이 덮어쓰지 않는다.</li>
 * </ul>
 * 이벤트에서는 만드는 곳이 가입 하나뿐이라, 늦게 처리된 가입 정보가 이미 반영된 변경 정보를 덮어쓰지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppearanceUserSyncUseCase {

    private final AppearanceUserRepository appearanceUserRepository;
    private final AppearanceUserSupport appearanceUserSupport;

    public void createIfAbsent(UserDto user) {
        if (appearanceUserRepository.existsById(user.id())) {
            log.debug("이미 복제된 출연 회원이라 가입 정보를 무시한다 userId={}", user.id());
            return;
        }
        appearanceUserRepository.save(AppearanceUser.from(user));
    }

    /**
     * @throws com.hapbang.appearance.domain.AppearanceException 출연 회원이 아직 없을 때({@code APPEARANCE_USER_NOT_FOUND})
     */
    public void update(UserDto user) {
        AppearanceUser appearanceUser = appearanceUserSupport.getById(user.id());
        appearanceUser.syncFrom(user);
    }

    /** 회원 원본 스냅샷 반영 (Backfill). 없으면 만들고, 있으면 회원 버전이 더 높을 때만 갱신한다 */
    public AppearanceUserSyncResult applySnapshot(UserDto user) {
        Optional<AppearanceUser> found = appearanceUserRepository.findById(user.id());
        if (found.isEmpty()) {
            appearanceUserRepository.save(AppearanceUser.from(user));
            return AppearanceUserSyncResult.CREATED;
        }
        AppearanceUser appearanceUser = found.get();
        Long before = appearanceUser.getMemberVersion();
        appearanceUser.syncFrom(user);
        return before != null && Objects.equals(before, appearanceUser.getMemberVersion())
                ? AppearanceUserSyncResult.IGNORED
                : AppearanceUserSyncResult.UPDATED;
    }
}
