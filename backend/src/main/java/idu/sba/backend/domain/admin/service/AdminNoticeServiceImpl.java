package idu.sba.backend.domain.admin.service;

import idu.sba.backend.domain.admin.dto.NoticeResponseDTO;
import idu.sba.backend.domain.admin.dto.SendNoticeResponseDTO;
import idu.sba.backend.domain.admin.entity.Notice;
import idu.sba.backend.domain.admin.repository.NoticeMailTaskRepository;
import idu.sba.backend.domain.admin.repository.NoticeRepository;
import idu.sba.backend.domain.notification.service.NotificationService;
import idu.sba.backend.domain.user.entity.User;
import idu.sba.backend.domain.user.entity.UserStatus;
import idu.sba.backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminNoticeServiceImpl implements AdminNoticeService {

    private final UserRepository userRepository;
    private final NoticeRepository noticeRepository;
    private final NoticeMailTaskRepository mailTaskRepository;
    private final NoticeMailTaskWriter mailTaskWriter;
    private final NotificationService notificationService;

    @Override
    @Transactional
    public SendNoticeResponseDTO sendNoticeToAll(Long senderId, String subject, String content, boolean urgent) {
        // 활성 회원 대상
        List<User> activeUsers = userRepository.findAll().stream()
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .toList();

        List<Long> userIds = activeUsers.stream().map(User::getId).toList();

        // 이메일은 긴급공지일 때만, 그것도 주소 있는 대상만. 일반 공지는 인앱 알림만.
        List<String> emails = urgent
                ? activeUsers.stream().map(User::getEmail).filter(e -> e != null && !e.isBlank()).toList()
                : List.of();

        // 대상 수: 긴급=이메일 대상, 일반=인앱 알림 대상
        int recipients = urgent ? emails.size() : userIds.size();
        Notice notice = noticeRepository.save(
                Notice.create(senderId, subject, content, recipients, urgent));

        if (urgent && !emails.isEmpty()) {
            mailTaskWriter.enqueue(notice.getId(), emails);
        }

        // 인앱 알림(종 아이콘)은 항상 즉시 생성 — noticeId로 공지와 엮어 삭제 때 함께 지운다
        notificationService.broadcast(userIds, urgent ? "🚨" : "📢", subject, content, "/app", notice.getId());

        if (!urgent || emails.isEmpty()) {
            notice.markSent(urgent ? 0 : userIds.size(), 0);
        }
        return new SendNoticeResponseDTO(notice.getId(), recipients);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NoticeResponseDTO> getNotices() {
        return noticeRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(NoticeResponseDTO::of)
                .toList();
    }

    // 이력 row + 인앱 알림 정리. 새 공지는 noticeId로 알림을 완전 삭제하고,
    // 링크 없는 예전 공지는 제목/내용으로 찾아 '삭제된 알림입니다'로 표시한다.
    // 이미 나간 이메일은 되돌릴 수 없다.
    @Override
    @Transactional
    public void deleteNotice(Long noticeId) {
        Notice notice = noticeRepository.findById(noticeId).orElse(null);
        mailTaskRepository.deleteByNoticeId(noticeId);
        notificationService.deleteByNoticeId(noticeId);        // 새 공지 알림: 완전 삭제
        if (notice != null) {                                  // 예전 공지 알림: 링크가 없어 문구로 매칭해 표시만 바꿈
            notificationService.markLegacyNoticeDeleted(notice.getSubject(), notice.getContent());
        }
        noticeRepository.deleteById(noticeId);
    }

}
