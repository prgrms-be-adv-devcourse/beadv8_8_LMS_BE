package com.hapbang.coupon.support;

import java.util.List;

import org.springframework.data.repository.Repository;

import com.hapbang.coupon.domain.CouponDltLog;

/** 테스트 준비·검증용 저장소. 운영 port에는 운영 코드가 쓰는 메서드만 두므로 테스트만 쓰는 기본 메서드를 여기 둔다 */
public interface CouponDltLogTestRepository extends Repository<CouponDltLog, Long> {

    <S extends CouponDltLog> S save(S entity);
    List<CouponDltLog> findAll();
}
