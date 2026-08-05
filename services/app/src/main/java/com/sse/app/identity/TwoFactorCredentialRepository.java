package com.sse.app.identity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TwoFactorCredentialRepository extends JpaRepository<TwoFactorCredential, String> {
}
