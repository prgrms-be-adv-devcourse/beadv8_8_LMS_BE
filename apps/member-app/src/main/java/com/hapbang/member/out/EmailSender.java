package com.hapbang.member.out;

/**
 * 메일 보내기 (#557). 운영은 Gmail SMTP({@link SmtpEmailSender}), 메일 계정이 없는 로컬·테스트는 로그로 남긴다({@link LoggingEmailSender}).
 * 실패하면 예외를 그대로 던진다(부르는 쪽이 사용자에게 다시 시도하라고 안내한다)
 */
public interface EmailSender {

    /** 텍스트 메일 한 통을 보낸다 */
    void send(String to, String subject, String text);
}
