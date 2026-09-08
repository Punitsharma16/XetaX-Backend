package com.xetax.crm.settings.service;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.email.EmailService;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.UnauthorizedException;
import com.xetax.crm.settings.entity.OrgSmtpSettings;
import com.xetax.crm.settings.repository.OrgSmtpSettingsRepository;
import com.xetax.crm.team.service.PermissionService;
import com.xetax.crm.whatsapp.service.SecretEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Panel-managed SMTP per organization. Admin sets it once from Profile;
 * every email (meeting invites, SEND_EMAIL automations) of that org then
 * goes out from THEIR address. Senders are built lazily and cached; the
 * cache entry is dropped whenever settings change.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgSmtpService {

    private final com.xetax.crm.contact.EmailLogRepository emailLogRepository;

    private final OrgSmtpSettingsRepository repository;
    private final SecretEncryptionService encryption;
    private final CurrentUserProvider currentUserProvider;
    private final PermissionService permissionService;
    private final EmailService emailService;

    private final Map<String, JavaMailSenderImpl> senderCache = new ConcurrentHashMap<>();

    private String ownerId() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new UnauthorizedException("Not authenticated");
        return id.toString();
    }

    private void requireAdmin() {
        if (!permissionService.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Email settings sirf admin badal sakta hai.");
        }
    }

    /** Masked view — password kabhi wapas nahi jaata. */
    public Map<String, Object> get() {
        Map<String, Object> out = new LinkedHashMap<>();
        var settings = repository.findByOwnerUserId(ownerId()).orElse(null);
        out.put("configured", settings != null);
        out.put("host", settings == null ? "" : settings.getHost());
        out.put("port", settings == null ? 587 : settings.getPort());
        out.put("username", settings == null ? "" : settings.getUsername());
        out.put("canEdit", permissionService.isAdmin());
        return out;
    }

    @Transactional
    public Map<String, Object> save(String host, Integer port, String username, String password) {
        requireAdmin();
        if (host == null || host.isBlank() || username == null || username.isBlank()) {
            throw new BadRequestException("SMTP host aur username (email) dono zaroori hain");
        }
        String owner = ownerId();
        OrgSmtpSettings settings = repository.findByOwnerUserId(owner)
                .orElseGet(() -> OrgSmtpSettings.builder().ownerUserId(owner).build());
        settings.setHost(host.trim());
        settings.setPort(port == null || port <= 0 ? 587 : port);
        settings.setUsername(username.trim());
        if (password != null && !password.isBlank()) {
            settings.setPasswordEncrypted(encryption.encrypt(password));
        } else if (settings.getPasswordEncrypted() == null) {
            throw new BadRequestException("Password zaroori hai (pehli baar set karte waqt)");
        }
        repository.save(settings);
        senderCache.remove(owner);
        return get();
    }

    @Transactional
    public void delete() {
        requireAdmin();
        String owner = ownerId();
        repository.findByOwnerUserId(owner).ifPresent(repository::delete);
        senderCache.remove(owner);
    }

    /** Apne aap ko test mail — turant pata chale credentials sahi hain ya nahi. */
    public Map<String, Object> sendTest(String toEmail) {
        requireAdmin();
        String owner = ownerId();
        if (!isConfiguredFor(owner)) {
            throw new BadRequestException("Pehle SMTP settings save karo");
        }
        try {
            sendAs(owner, toEmail, "XetaX test email",
                    "Ye ek test email hai — aapki email settings sahi kaam kar rahi hain! ✅");
            return Map.of("sent", true, "to", toEmail);
        } catch (Exception e) {
            return Map.of("sent", false, "error", rootMessage(e));
        }
    }

    /* ---------------------------------------------- org-aware send engine */

    public boolean isConfiguredFor(String ownerUserId) {
        return repository.findByOwnerUserId(ownerUserId).isPresent()
                || emailService.isConfigured();
    }

    /** Org ki SMTP se bhejo; org ne set nahi ki to global .env wali (agar ho). */
    public void sendAs(String ownerUserId, String to, String subject, String body) {
        try {
            doSend(ownerUserId, to, subject, body);
            logEmail(ownerUserId, to, subject, body, "SENT", null);
        } catch (RuntimeException e) {
            logEmail(ownerUserId, to, subject, body, "FAILED", rootMessage(e));
            throw e;
        }
    }

    private void doSend(String ownerUserId, String to, String subject, String body) {
        OrgSmtpSettings settings = repository.findByOwnerUserId(ownerUserId).orElse(null);
        if (settings == null) {
            emailService.send(to, subject, body); // global fallback (ya log-only)
            return;
        }
        JavaMailSenderImpl sender = senderCache.computeIfAbsent(ownerUserId,
                key -> buildSender(settings));
        var message = new org.springframework.mail.SimpleMailMessage();
        message.setFrom(settings.getUsername());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
        log.info("Org email sent to {} from {}", to, settings.getUsername());
    }

    /* Every outgoing email leaves a row — the contact page shows these as
       "email history with this customer". Logging must never break sending. */
    private void logEmail(String owner, String to, String subject, String body,
                          String status, String error) {
        try {
            emailLogRepository.save(com.xetax.crm.contact.EmailLog.builder()
                    .ownerUserId(owner)
                    .toEmail(to)
                    .subject(subject)
                    .body(body)
                    .status(status)
                    .error(error)
                    .createdAt(java.time.LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("Email log write failed: {}", e.getMessage());
        }
    }

    /** Email with one attachment — requires the org's own SMTP (no global fallback). */
    public void sendWithAttachment(String ownerUserId, String to, String subject, String body,
                                   String filename, byte[] bytes, String contentType) {
        OrgSmtpSettings settings = repository.findByOwnerUserId(ownerUserId).orElse(null);
        if (settings == null) {
            throw new com.xetax.crm.common.exception.BadRequestException(
                    "Sending attachments needs your own email account — add it under Profile → Email (SMTP).");
        }
        JavaMailSenderImpl sender = senderCache.computeIfAbsent(ownerUserId,
                key -> buildSender(settings));
        try {
            jakarta.mail.internet.MimeMessage mime = sender.createMimeMessage();
            org.springframework.mail.javamail.MimeMessageHelper helper =
                    new org.springframework.mail.javamail.MimeMessageHelper(mime, true);
            helper.setFrom(settings.getUsername());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body == null ? "" : body);
            helper.addAttachment(filename, new org.springframework.core.io.ByteArrayResource(bytes), contentType);
            sender.send(mime);
            logEmail(ownerUserId, to, subject, (body == null ? "" : body) + "\n[Attachment: " + filename + "]",
                    "SENT", null);
        } catch (jakarta.mail.MessagingException | org.springframework.mail.MailException e) {
            logEmail(ownerUserId, to, subject, (body == null ? "" : body) + "\n[Attachment: " + filename + "]",
                    "FAILED", rootMessage(e));
            throw new com.xetax.crm.common.exception.BadRequestException(
                    "Email failed to send — check your SMTP settings (Profile → Email).");
        }
    }

    private JavaMailSenderImpl buildSender(OrgSmtpSettings settings) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(settings.getHost());
        sender.setPort(settings.getPort());
        sender.setUsername(settings.getUsername());
        sender.setPassword(encryption.decrypt(settings.getPasswordEncrypted()));
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.connectiontimeout", "8000");
        props.put("mail.smtp.timeout", "10000");
        return sender;
    }

    private String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null ? "send failed" : message;
    }
}
