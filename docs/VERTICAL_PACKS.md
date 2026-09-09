# Vertical packs & the Sales Playbook — developer notes

## Packs
A pack is plain JSON (`crm/src/main/resources/packs/<key>.json`, class `template.model.PackDefinition`).
Adding an industry = adding a file; nothing else. The catalog loads `classpath*:packs/*.json` at boot and
validates each one (`PackCatalogService.validate`): unique field keys, unique stage codes/sequences, one
default stage, and every stage/field a rule or hint references must exist inside the pack.

Sections: `fields`, `stages`, `automations` (created OFF), `whatsappTemplates` (saved as local drafts in
`message_template_drafts`, submitted to Meta via `POST /api/templates/drafts/{id}/submit`), `agent`
(AiAgent + AgentChannelConfig targeting the new form, `stageHints` by stage code, optional `knowledge`
text indexed into Qdrant), `playbook` (rules use stage **codes**; resolved to ids on apply).

Install: `POST /api/templates/{key}/apply` with `{name?, includeAgent?, includePlaybook?,
includeAutomations?, includeWhatsapp?}`. Every install is recorded in `pack_installs` so the gallery can say
"Installed". Custom packs (`vertical_packs`, per owner) come from `POST /api/templates/export/{formId}`
(snapshot of a form incl. its agent/playbook/drafts) or `POST /api/templates/import` (raw JSON).
Custom keys that collide with a built-in key get a `_custom` suffix.

## Playbook
Tables: `sales_playbooks` (one per owner+form, `rules_json`), `playbook_runs` (one row per rule×record —
counts, last channel/outcome, and `next_eligible_at` for follow-ups the bot books from a chat).

`PlaybookEngine.tick()` runs every 60 s: for each active playbook → each active rule → Mongo query
(`formId` + stage filter, ≤300 records, oldest-updated first) → trigger check (`NO_REPLY` uses the
latest of ChatSession.lastCustomerAt / WhatsAppConversation.lastInboundAt / record.createdAt) →
conditions → caps (rule `maxRepeats`, playbook `maxFollowUps` counted over message channels) → skip if a
human is driving the chat → `PlaybookActions.execute`. At most 40 actions per playbook per tick.
Quiet hours use the playbook timezone (default Asia/Kolkata).

Delivery (`MessageDeliveryService`): WhatsApp text (window open) → approved template named on the rule
(`WhatsAppMessagingService.sendTemplateAsOwner`, body params from `templateParams` field keys, default the
name field) → org/global SMTP → `TaskItem` for the assignee/owner. Documents: WhatsApp → email attachment
(org SMTP only) → task.

AI composition: `PlaybookActions.compose` uses the playbook's agent persona + goal + record block; metered
with `AiQuotaService.tryConsumeAgent` and falls back to the static text when quota is exhausted or the
LLM fails.

Bot hooks (`BotConversationService`): with an active playbook on the agent's target form, the system prompt
gets a `SALES PLAYBOOK` block (goal, missing qualification fields, next-step guidance) and two actions:
`send_document` (once per 24 h per lead) and `book_followup` (`playbook_runs` row with `next_eligible_at`
+ a task). Both run inside the existing ≤4-actions-per-turn cap.

## Deployment
All new tables are plain JPA entities (`ddl-auto: update` creates them on first boot; new enum-like
columns are `varchar`). No migration, no new service, no new env var. Automation action `CREATE_TASK` is
a new enum value on a `varchar(50)` column, so old rows are untouched. Rebuild the app container as usual
(`deploy/update.sh`).
