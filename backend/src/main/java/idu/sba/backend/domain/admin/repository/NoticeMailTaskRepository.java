package idu.sba.backend.domain.admin.repository;

import idu.sba.backend.domain.admin.entity.NoticeMailTask;
import idu.sba.backend.domain.admin.entity.NoticeMailTask.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface NoticeMailTaskRepository extends JpaRepository<NoticeMailTask, Long> {
    @Query("""
            select t.id from NoticeMailTask t
            where (t.status = :pending and t.nextAttemptAt <= :now)
               or (t.status = :inProgress and t.leaseUntil <= :now)
            order by t.id
            """)
    List<Long> findReadyIds(@Param("pending") Status pending,
                            @Param("inProgress") Status inProgress,
                            @Param("now") LocalDateTime now,
                            Pageable pageable);

    @Modifying
    @Transactional
    @Query("""
            update NoticeMailTask t
               set t.status = :inProgress, t.claimToken = :token,
                   t.leaseUntil = :leaseUntil, t.attemptCount = t.attemptCount + 1
             where t.id = :id
               and ((t.status = :pending and t.nextAttemptAt <= :now)
                 or (t.status = :inProgress and t.leaseUntil <= :now))
            """)
    int claim(@Param("id") Long id, @Param("pending") Status pending,
              @Param("inProgress") Status inProgress, @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil, @Param("token") String token);

    @Modifying
    @Transactional
    @Query("""
            update NoticeMailTask t
               set t.status = :sent, t.claimToken = null, t.leaseUntil = null
             where t.id = :id and t.status = :inProgress and t.claimToken = :token
            """)
    int markSent(@Param("id") Long id, @Param("inProgress") Status inProgress,
                 @Param("sent") Status sent, @Param("token") String token);

    @Modifying
    @Transactional
    @Query("""
            update NoticeMailTask t
               set t.status = :nextStatus, t.nextAttemptAt = :nextAttemptAt,
                   t.claimToken = null, t.leaseUntil = null
             where t.id = :id and t.status = :inProgress and t.claimToken = :token
            """)
    int markFailure(@Param("id") Long id, @Param("inProgress") Status inProgress,
                    @Param("nextStatus") Status nextStatus,
                    @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                    @Param("token") String token);

    long countByNoticeIdAndStatus(Long noticeId, Status status);

    void deleteByNoticeId(Long noticeId);
}
