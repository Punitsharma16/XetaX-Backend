package com.xetax.crm.playbook;

import java.util.List;

/** Body of PUT /api/playbooks/form/{formId}. */
public record PlaybookRequest(Long agentId, Boolean active, String goal, List<String> qualificationKeys,
                              Integer quietStart, Integer quietEnd, String timezone, Integer maxFollowUps,
                              Long quotationDocumentId, List<PlaybookRule> rules) {}
