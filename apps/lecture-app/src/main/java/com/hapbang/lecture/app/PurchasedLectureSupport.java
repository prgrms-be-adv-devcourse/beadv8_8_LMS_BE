package com.hapbang.lecture.app;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.hapbang.lecture.app.dto.PurchasedLectureResponse;
import com.hapbang.lecture.domain.Lecture;
import com.hapbang.lecture.domain.OrderItem;
import com.hapbang.lecture.out.LectureOrderRepository;
import com.hapbang.lecture.out.LectureRepository;

import lombok.RequiredArgsConstructor;

/**
 * 구매자가 시청할 수 있는 강의 목록 (D39). 응답 DTO 변환은 호출한 Facade의 트랜잭션 안에서 끝난다.
 * 보유 항목(구매·구매 확정·지급)마다 한 줄이고, 판매자가 삭제한 강의도 포함한다(D5). 제목·썸네일은 현재 강의 기준이다.
 */
@Service
@RequiredArgsConstructor
public class PurchasedLectureSupport {

    private final LectureOrderRepository lectureOrderRepository;
    private final LectureRepository lectureRepository;
    private final LectureThumbnailSupport lectureThumbnailSupport;

    /** 최근 구매순. 정렬은 고정이고 요청의 정렬은 무시한다. 페이지의 강의와 썸네일은 한 번에 읽는다 */
    public Page<PurchasedLectureResponse> getPurchasedLectures(Long buyerUserId, Pageable pageable) {
        Page<OrderItem> items = lectureOrderRepository.findHeldItems(buyerUserId,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
        Map<Long, Lecture> lectures = lectureRepository.findAllById(items.map(OrderItem::getLectureId).toSet())
                .stream()
                .collect(Collectors.toMap(Lecture::getId, Function.identity()));
        Map<Long, String> thumbnailUrls = lectureThumbnailSupport.urlsOf(
                lectures.values().stream().map(Lecture::getThumbnailFileId).collect(Collectors.toSet()));
        return items.map(item -> {
            Lecture lecture = lectures.get(item.getLectureId());
            return PurchasedLectureResponse.of(item, lecture, thumbnailUrls.get(lecture.getThumbnailFileId()));
        });
    }
}
