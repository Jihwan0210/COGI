package idu.sba.backend.domain.admin.dto;

import idu.sba.backend.domain.admin.entity.Notice;

import java.time.LocalDateTime;

// 사용자에게 보여줄 공지 한 건 (제목/내용/발송일만 — 발송 통계는 노출 안 함)
public record UserNoticeResponseDTO(
        Long id, String subject, String content, LocalDateTime sentAt, boolean urgent
) {
    public static UserNoticeResponseDTO of(Notice n) {
        return new UserNoticeResponseDTO(n.getId(), n.getSubject(), n.getContent(),
                n.getSentAt() != null ? n.getSentAt() : n.getCreatedAt(), n.isUrgent());
    }
}
