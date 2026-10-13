package com.hapbang.appearance.out;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.appearance.domain.AppearanceRequest;
import com.hapbang.appearance.domain.AppearanceRequestStatus;

public interface AppearanceRequestRepository extends JpaRepository<AppearanceRequest, Long> {

    /** 같은 호스트·게스트 사이에 진행 중인 요청서가 있는지 */
    boolean existsByHostIdAndGuestIdAndStatusIn(Long hostId, Long guestId, Collection<AppearanceRequestStatus> statuses);

    /** 보낸 요청 목록 */
    Page<AppearanceRequest> findByHostIdAndStatusIn(Long hostId, Collection<AppearanceRequestStatus> statuses,
                                                    Pageable pageable);

    /** 받은 요청 목록 */
    Page<AppearanceRequest> findByGuestIdAndStatusIn(Long guestId, Collection<AppearanceRequestStatus> statuses,
                                                     Pageable pageable);
}
