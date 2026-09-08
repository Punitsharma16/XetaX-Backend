# Automation Module — Changes Record

Date: 17-Aug-2026 · Module: `crm` (`com.xetax.crm.automation`) · Build: `mvnw compile` ✅ BUILD SUCCESS

## What was asked

1. Ek hi table se automation chale — event ka enum (create record, update record, stage changed…), email ke liye message field, stage ka field (kis stage par kya ho), aur form field ki value increase/decrease karne ka action.
2. Flow ko thoda better banana allowed tha.

## Design decisions (the "better flow" part)

- **Email/Task triggers nahi, actions hain.** Trigger = *kab* (RECORD_CREATED / RECORD_UPDATED / STAGE_CHANGED), Action = *kya ho* (email bhejo, field badlo…). Isliye `SEND_EMAIL` naya **action type** bana, trigger enum untouched raha. (CREATE_TASK abhi nahi banaya — backend me task ka koi module hi nahi hai; jab task entity aayegi tab ek aur executor add ho jayega.)
- **Single-table rule.** `automations` table ab apna action khud carry karti hai — ek row = "is event par, (is stage par,) ye action". Common case me `automation_actions` join ki zaroorat nahi.
- **Legacy safe.** Purani `automation_actions` rows waise hi chalti hain: agar automation ka inline `actionType` NULL hai to engine pehle jaisa actions-table fallback use karta hai. Conditions table bhi untouched.
- **Increment/Decrement = ek hi executor.** Alag INCREMENT/DECREMENT enums ki jagah single `ADJUST_FIELD` — amount signed hota hai (`"10"` badhao, `"-5"` ghatao, blank = +1).

## Schema changes (ddl-auto=update se columns khud ban jayenge)

`automations` table — naye columns:

| Column | Type | Purpose |
|---|---|---|
| `trigger_stage_id` | bigint (nullable) | STAGE_CHANGED par sirf is stage pe fire ho; NULL = har stage move par |
| `action_type` | varchar(50) | Inline action enum (`UPDATE_FIELD`, `CHANGE_STAGE`, `ASSIGN_USER`, `SEND_EMAIL`, `ADJUST_FIELD`) |
| `action_field_id` | bigint FK → form_fields | Action ka target field; SEND_EMAIL me recipient-email wala field |
| `action_value` | varchar(2000) | UPDATE_FIELD: nayi value · CHANGE_STAGE: stage id · ASSIGN_USER: user id · ADJUST_FIELD: signed amount |
| `email_subject` | varchar(500) | SEND_EMAIL subject (optional, default "Notification") |
| `email_message` | varchar(4000) | SEND_EMAIL body — `{fieldKey}` placeholders record ki values se resolve hote hain |

`automation_actions` table (legacy parity): `email_subject`, `email_message` columns add hue.

## File-by-file changes

### Edited

| File | Change |
|---|---|
| `enums/AutomationActionType.java` | + `SEND_EMAIL`, + `ADJUST_FIELD` (javadoc ke saath) |
| `entity/Automation.java` | + `triggerStageId`, + inline action fields (`actionType`, `actionField`, `actionValue`, `emailSubject`, `emailMessage`) |
| `entity/AutomationAction.java` | + `emailSubject`, `emailMessage` (legacy path bhi email bhej sake) |
| `action/ActionExecutor.java` | + `default boolean mutatesRecord()` — engine sirf mutating action ke baad record save kare (SEND_EMAIL par faltu Mongo save nahi hota) |
| `engine/AutomationEngineImpl.java` | (1) STAGE_CHANGED + `triggerStageId` filter (2) inline action first, actions-table fallback (3) **per-automation try/catch** — ek automation phate to baaki automations aur API response bach jate hain, error log hota hai (4) `mutatesRecord()` based save (5) `toInlineAction()` adapter jisse purane executors bina badle reuse hue |
| `dto/AutomationRequest.java` | + `triggerStageId`, `actionType`, `actionFieldId`, `actionValue`, `emailSubject`, `emailMessage` |
| `dto/AutomationResponse.java` | + same fields + `actionFieldName`/`actionFieldKey` |
| `mapper/AutomationMapper.java` | + `actionField.id/label/fieldKey` mappings |
| `service/AutomationServiceImpl.java` | create/update ab rule fields persist karte hain; validation: trigger stage aur action field **usi form ke** hone chahiye (warna 400/404) |
| `dto/AutomationActionRequest.java` / `AutomationActionResponse.java` | + `emailSubject`, `emailMessage` |
| `service/AutomationActionServiceImpl.java` | builder me dono email fields |
| `pom.xml` | + `spring-boot-starter-mail` |

### New files

| File | Purpose |
|---|---|
| `common/email/EmailService.java` | `send(to, subject, body)` interface |
| `common/email/EmailServiceImpl.java` | JavaMailSender `ObjectProvider` se — SMTP configure nahi hai to app crash nahi hoti, email log ho jaati hai. From = `spring.mail.username` |
| `automation/action/SendEmailActionExecutor.java` | Recipient = `actionField` wale record field ki value; subject/body me `{fieldKey}` placeholders; `mutatesRecord() = false` |
| `automation/action/AdjustFieldActionExecutor.java` | Current numeric value ± signed amount; missing/non-numeric current = 0; whole numbers long ke roop me store (5, 5.0 nahi) |

## API — ab automation aise banti hai (ek hi call)

```jsonc
// POST /api/automations   (existing AutomationController route)
// "Stage 'Won' pe aate hi customer ko email + score 10 badhao" — 2 automations:
{
  "name": "Won email",
  "formId": 1,
  "trigger": "STAGE_CHANGED",
  "triggerStageId": 7,              // Won stage
  "actionType": "SEND_EMAIL",
  "actionFieldId": 12,              // record ka email field (recipient)
  "emailSubject": "Congrats {name}!",
  "emailMessage": "Hi {name}, aapki deal {amount} ki close ho gayi."
}
{
  "name": "Won score bump",
  "formId": 1,
  "trigger": "STAGE_CHANGED",
  "triggerStageId": 7,
  "actionType": "ADJUST_FIELD",
  "actionFieldId": 15,              // numeric "score" field
  "actionValue": "10"               // "-10" hota to decrease
}
```

## SMTP config (email actually bhejne ke liye)

`application.yaml` me add karo (bina iske SEND_EMAIL sirf log hota hai, fail nahi):

```yaml
spring:
  mail:
    host: smtp.gmail.com
    port: 587
    username: you@example.com
    password: <app-password>
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
```

## Not changed

- `AutomationTrigger` enum, conditions module, controllers ke routes, RecordServiceImpl ke trigger points — sab waise ke waise.
- Legacy multi-action chains (`automation_actions` + executionOrder) — engine fallback me poori tarah supported.

---

# Frontend (xetax-crm-ui) — Changes Record

Date: 17-Aug-2026 · `ng build` ✅ (sirf pre-existing budget warnings)

## 1. Automation editor — simple single-rule page

`features/automations/automation-detail/` **poora rewrite**. Pehle conditions aur
actions ke do alag panels the, dono ke apne Save — tricky. Ab ek hi card me
seedha rule padhta hai:

- **When** — trigger dropdown; STAGE_CHANGED chunne par "On stage" dropdown (Any stage / specific)
- **If (optional)** — conditions rows (field / equals / value) — pehle jaisi, par ab same Save me
- **Then** — ek action dropdown, aur uske hisaab se contextual inputs:
  - Update a field → field + new value (select-type field ho to options ka dropdown)
  - Change the stage → stage dropdown
  - Assign a user → users dropdown (Auth service se)
  - Send an email → recipient field + subject + message; message ke neeche `{fieldKey}` insert-chips
  - Increase/decrease a field → field + Direction (Increase/Decrease) + amount — signed value backend jaata hai
- **Ek hi "Save rule" button** — automation (PUT) + conditions ek saath save

## 2. Record edit — popup hata, full page bana

- **Naya** `features/records/record-detail/` + route `records/:slug/:recordId`
  (page baad me grow karne ke liye — activity, related data, etc.)
- Upar **stage pipeline strip**: saare stages pill ke roop me; kisi par click =
  record us stage me move (STAGE_CHANGED automations fire hoti hain)
- **Current stage blink karta hai** (CSS pulse animation, stage ke apne color me)
- Neeche dynamic form (same DynamicFieldComponent) + Cancel / Save record
- `records-list`: Edit icon ab detail page kholta hai; **create** ka modal jaisa tha waisa hai
- `record.service.ts`: + `getById(id)`

## 3. Backend support endpoint

- `RecordController`: + `GET /api/record/{id}` (service me getById pehle se tha,
  controller mapping nahi thi — detail page refresh ke liye zaroori)

## 4. Models (`core/models/crm.model.ts`)

- `AutomationActionType`: + `SEND_EMAIL`, `ADJUST_FIELD`
- `AutomationRequest/Response`: + `triggerStageId`, `actionType`, `actionFieldId`,
  `actionValue`, `emailSubject`, `emailMessage` (+ response me actionFieldName/Key)
- `AutomationActionRequest/Response`: + `emailSubject`, `emailMessage` (legacy)

## 5. Global button spacing (`src/styles.css`)

Project-bhar ke Submit/Cancel jaise button pairs ke beech ab hamesha 0.5rem gap —
plain-flow siblings ke liye `.btn + .btn` margin; jahan parent flex `gap-*` de
raha hai wahan double space na ho iska dhyan rakha; `.btn-group` glued rehta hai.
