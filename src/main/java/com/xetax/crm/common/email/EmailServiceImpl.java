package com.xetax.crm.common.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/*
 * JavaMailSender is only auto-configured when spring.mail.host is set, so it is
 * pulled through an ObjectProvider: without SMTP config the app still boots and
 * SEND_EMAIL actions degrade to a log line instead of failing the automation.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    @Value("${spring.mail.host:}")
    private String smtpHost;

    @Override
    public boolean isConfigured() {
        return !smtpHost.isBlank() && mailSenderProvider.getIfAvailable() != null;
    }

    @Override
    public void send(String to, String subject, String body) {

        JavaMailSender sender = mailSenderProvider.getIfAvailable();

        if (sender == null || smtpHost.isBlank()) {
            log.warn(
                    "SMTP not configured (spring.mail.*) — email to {} not sent. Subject: [{}] Body: {}",
                    to, subject, body
            );
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        if (!fromAddress.isBlank()) {
            message.setFrom(fromAddress);
        }
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);

        sender.send(message);
        log.info("Email sent to {} from {} with subject [{}]", to, fromAddress, subject);
    }
}
