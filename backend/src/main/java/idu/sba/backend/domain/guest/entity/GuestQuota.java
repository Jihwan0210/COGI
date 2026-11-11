package idu.sba.backend.domain.guest.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

// JPA가 테이블 구조를 관리한다. 동시성 제어 SQL은 GuestQuotaRepository에 둔다.
@Entity
@Table(name = "guest_quotas")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuestQuota {
    @Id
    @Column(name = "guest_token", length = 255,
            columnDefinition = "varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")
    private String guestToken;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "window_id", nullable = false, length = 36)
    private String windowId;
}
