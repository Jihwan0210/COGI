package idu.sba.backend.domain.admin.controller;

import idu.sba.backend.domain.admin.dto.UserNoticeResponseDTO;
import idu.sba.backend.domain.admin.repository.NoticeRepository;
import idu.sba.backend.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 공지와 인앱 알림이 커밋되면 메일 재시도 중이어도 사용자에게 공지를 보여준다.
// 조회 전용 한 줄이라 서비스 없이 리포지토리 직접 사용.
@RestController
@RequestMapping("/api/notices")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeRepository noticeRepository;

    @GetMapping
    public ApiResponse<List<UserNoticeResponseDTO>> list() {
        return ApiResponse.ok(
                noticeRepository.findAllByOrderByCreatedAtDesc()
                        .stream().map(UserNoticeResponseDTO::of).toList());
    }
}
