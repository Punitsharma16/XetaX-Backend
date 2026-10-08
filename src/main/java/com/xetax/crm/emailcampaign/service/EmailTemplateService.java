package com.xetax.crm.emailcampaign.service;

import com.xetax.crm.auth.security.CurrentUserProvider;
import com.xetax.crm.common.exception.BadRequestException;
import com.xetax.crm.common.exception.ResourceNotFoundException;
import com.xetax.crm.common.exception.UnauthorizedException;
import com.xetax.crm.emailcampaign.dto.EmailTemplateRequest;
import com.xetax.crm.emailcampaign.dto.EmailTemplateResponse;
import com.xetax.crm.emailcampaign.entity.EmailTemplate;
import com.xetax.crm.emailcampaign.repository.EmailTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Saved subject + body pairs for email campaigns.
 *
 * <p>Every read and write is scoped to the caller's own workspace; an id from
 * another owner reads as missing rather than forbidden, which is how the
 * campaign endpoints already behave.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailTemplateService {

    private static final int MAX_NAME = 150;
    private static final int MAX_SUBJECT = 500;

    private final EmailTemplateRepository templateRepository;
    private final CurrentUserProvider currentUserProvider;

    @Transactional
    public EmailTemplateResponse create(EmailTemplateRequest request) {
        String owner = currentUserId();
        String name = requireText(request.getName(), "Template name is required", MAX_NAME);
        String subject = requireText(request.getSubject(), "Subject is required", MAX_SUBJECT);
        String body = requireBody(request.getBody());

        if (templateRepository.existsByOwnerUserIdAndNameIgnoreCase(owner, name)) {
            throw new BadRequestException("A template named \"" + name + "\" already exists");
        }

        EmailTemplate saved = templateRepository.save(EmailTemplate.builder()
                .ownerUserId(owner)
                .name(name)
                .subject(subject)
                .body(body)
                .build());
        log.info("Email template {} created for owner {}", saved.getId(), owner);
        return toResponse(saved);
    }

    @Transactional
    public EmailTemplateResponse update(Long id, EmailTemplateRequest request) {
        String owner = currentUserId();
        EmailTemplate template = mine(id, owner);

        String name = requireText(request.getName(), "Template name is required", MAX_NAME);
        // Renaming onto another template's name would make the picker ambiguous.
        Optional<EmailTemplate> clash = templateRepository.findByOwnerUserIdAndNameIgnoreCase(owner, name);
        if (clash.isPresent() && !clash.get().getId().equals(id)) {
            throw new BadRequestException("A template named \"" + name + "\" already exists");
        }

        template.setName(name);
        template.setSubject(requireText(request.getSubject(), "Subject is required", MAX_SUBJECT));
        template.setBody(requireBody(request.getBody()));
        return toResponse(templateRepository.save(template));
    }

    @Transactional(readOnly = true)
    public List<EmailTemplateResponse> list() {
        return templateRepository.findByOwnerUserIdOrderByIdDesc(currentUserId())
                .stream().map(EmailTemplateService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public EmailTemplateResponse get(Long id) {
        return toResponse(mine(id, currentUserId()));
    }

    @Transactional
    public void delete(Long id) {
        String owner = currentUserId();
        templateRepository.delete(mine(id, owner));
        log.info("Email template {} deleted for owner {}", id, owner);
    }

    /**
     * The subject and body a campaign should start from, or empty when no
     * template was chosen. Campaigns copy the text — see {@link EmailTemplate}.
     */
    @Transactional(readOnly = true)
    public Optional<EmailTemplate> findForCampaign(Long templateId, String ownerUserId) {
        if (templateId == null) {
            return Optional.empty();
        }
        return Optional.of(mine(templateId, ownerUserId));
    }

    private EmailTemplate mine(Long id, String owner) {
        if (id == null) {
            throw new BadRequestException("Template id is required");
        }
        return templateRepository.findByIdAndOwnerUserId(id, owner)
                .orElseThrow(() -> new ResourceNotFoundException("Template not found"));
    }

    private static String requireText(String value, String message, int max) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(message);
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new BadRequestException(message.replace(" is required", "")
                    + " must be " + max + " characters or fewer");
        }
        return trimmed;
    }

    private static String requireBody(String body) {
        if (body == null || body.isBlank()) {
            throw new BadRequestException("Message body is required");
        }
        return body;
    }

    private String currentUserId() {
        UUID id = currentUserProvider.currentDataOwnerIdOrNull();
        if (id == null) throw new UnauthorizedException("Not authenticated");
        return id.toString();
    }

    static EmailTemplateResponse toResponse(EmailTemplate t) {
        return EmailTemplateResponse.builder()
                .id(t.getId())
                .name(t.getName())
                .subject(t.getSubject())
                .body(t.getBody())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .build();
    }
}
