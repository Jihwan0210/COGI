package idu.sba.backend.domain.admin.service;

final class NoticeMailContent {
    private NoticeMailContent() {}

    static String render(String subject, String content) {
        return """
            <span style="display:inline-block;background:#ffd23f;color:#1b2a4a;border:2px solid #1b2a4a;\
            font-size:11px;font-weight:bold;letter-spacing:1px;padding:2px 9px;margin-bottom:12px;">🚨 긴급공지</span>
            <h2 style="margin:0 0 16px;color:#1b2a4a;font-size:19px;line-height:1.35;">%s</h2>
            <div style="color:#40507a;font-size:11px;font-weight:bold;margin-bottom:8px;">내용</div>
            <div style="border:2px solid #1b2a4a;background:#ffffff;padding:14px 16px;box-shadow:3px 3px 0 #1b2a4a;\
            color:#1b2a4a;font-size:14px;line-height:1.8;">%s</div>
            """.formatted(escape(subject), escape(content).replace("\n", "<br>"));
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
