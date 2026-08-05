package com.sse.app.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserDeviceRepository extends JpaRepository<UserDevice, String> {
    Optional<UserDevice> findByUserIdAndDeviceToken(String userId, String deviceToken);
    List<UserDevice> findByUserIdAndActiveIsTrue(String userId);
    List<UserDevice> findByUserIdOrderByLastSeenAtDesc(String userId);
}
