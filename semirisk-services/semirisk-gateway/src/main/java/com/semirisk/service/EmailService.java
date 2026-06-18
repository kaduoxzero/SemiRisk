package com.semirisk.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * QQ SMTP 邮件服务。
 *
 * <p>发送方固定为 3514938226@qq.com，通过 QQ 邮箱授权码认证。</p>
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailService(JavaMailSender mailSender,
                        @Value("${semirisk.mail.from:3514938226@qq.com}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    /** 发送注册验证码邮件。 */
    public void sendVerificationCode(String to, String code) {
        sendHtml(to, "SemiRisk 注册验证码",
                "<h2>SemiRisk 注册验证码</h2>"
                        + "<p>您的验证码为：<strong style=\"font-size:24px;color:#3b82f6;\">%s</strong></p>"
                        + "<p>验证码 5 分钟内有效，每个邮箱最多尝试 2 次。</p>"
                        + "<p style=\"color:#94a3b8;font-size:12px;\">如果您没有注册 SemiRisk，请忽略此邮件。</p>",
                code);
    }

    /** 发送忘记密码验证码邮件。 */
    public void sendPasswordResetCode(String to, String code) {
        sendHtml(to, "SemiRisk 密码重置验证码",
                "<h2>SemiRisk 密码重置验证码</h2>"
                        + "<p>您的验证码为：<strong style=\"font-size:24px;color:#3b82f6;\">%s</strong></p>"
                        + "<p>验证码 15 分钟内有效。</p>"
                        + "<p style=\"color:#94a3b8;font-size:12px;\">如果您没有重置密码的需求，请忽略此邮件。</p>",
                code);
    }

    private void sendHtml(String to, String subject, String bodyTemplate, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(String.format(bodyTemplate, code), true);
            mailSender.send(message);
            log.info("Email sent to {} for subject '{}'", to, subject);
        } catch (MessagingException ex) {
            log.error("Failed to send email to {} for subject '{}'", to, subject, ex);
            throw new RuntimeException("邮件发送失败：" + ex.getMessage(), ex);
        }
    }
}
