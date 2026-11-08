package com.hapbang.lecture.in;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hapbang.lecture.app.LectureSyncInternalFacade;
import com.hapbang.lecture.global.response.RsData;
import com.hapbang.shared.lecture.dto.CartItemSyncPageDto;
import com.hapbang.shared.lecture.dto.LecturePurchaseSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureReviewSyncPageDto;
import com.hapbang.shared.lecture.dto.LectureSyncPageDto;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/internal/lectures/sync")
@RequiredArgsConstructor
public class ApiV1LectureSyncInternalController implements ApiV1LectureSyncInternalSpec {

    private final LectureSyncInternalFacade lectureSyncInternalFacade;

    @Override
    @GetMapping("/lectures")
    public RsData<LectureSyncPageDto> getLectures(@RequestParam(name = "afterId", required = false) Long afterId,
                                                  @RequestParam(name = "size", defaultValue = "100") int size) {
        return RsData.ok("강의 동기화 목록을 조회했습니다.", lectureSyncInternalFacade.getLectures(afterId, size));
    }

    @Override
    @GetMapping("/purchases")
    public RsData<LecturePurchaseSyncPageDto> getPurchases(
            @RequestParam(name = "afterId", required = false) Long afterId,
            @RequestParam(name = "size", defaultValue = "100") int size) {
        return RsData.ok("구매 동기화 목록을 조회했습니다.", lectureSyncInternalFacade.getPurchases(afterId, size));
    }

    @Override
    @GetMapping("/cart-items")
    public RsData<CartItemSyncPageDto> getCartItems(@RequestParam(name = "afterId", required = false) Long afterId,
                                                    @RequestParam(name = "size", defaultValue = "100") int size) {
        return RsData.ok("장바구니 항목 동기화 목록을 조회했습니다.", lectureSyncInternalFacade.getCartItems(afterId, size));
    }

    @Override
    @GetMapping("/reviews")
    public RsData<LectureReviewSyncPageDto> getReviews(@RequestParam(name = "afterId", required = false) Long afterId,
                                                       @RequestParam(name = "size", defaultValue = "100") int size) {
        return RsData.ok("리뷰 동기화 목록을 조회했습니다.", lectureSyncInternalFacade.getReviews(afterId, size));
    }
}
