package com.hapbang.appearance.out;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hapbang.appearance.domain.AppearanceUser;

public interface AppearanceUserRepository extends JpaRepository<AppearanceUser, Long> {
}
