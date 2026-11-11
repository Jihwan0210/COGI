package idu.sba.backend.domain.admin.repository;

import idu.sba.backend.domain.admin.entity.Notice;
import idu.sba.backend.domain.admin.entity.NoticeStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

    // 관리자 공지 이력 — 최신순 (목록 표시용)
    List<Notice> findAllByOrderByCreatedAtDesc();

    List<Notice> findByStatusAndUrgentTrue(NoticeStatus status);
}
