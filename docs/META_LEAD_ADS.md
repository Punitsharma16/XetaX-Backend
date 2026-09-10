# Facebook & Instagram Lead Ads — setup and Meta requirements

What this gives a client: every submission of a Facebook/Instagram **lead form ad**
becomes a CRM record within seconds (so the playbook can follow up while the lead is
still warm), and the panel shows what each campaign spent per lead and **per sale** —
a number Ads Manager cannot produce, because it does not know which lead paid.

Ads are still created by the client in Ads Manager. XetaX only *reads* leads and spend.
There is no ad-creation feature, on purpose: it needs `ads_management`, which Meta
reviews far more strictly, and it duplicates a tool the client already uses.

---

## 1. What Meta wants, feature by feature

| You want | Permission | Review needed | Notes |
|---|---|---|---|
| Read lead-form submissions | `leads_retrieval` | Advanced Access | Also needs the Lead Ads Terms signed (§3) |
| List the person's Pages | `pages_show_list` | Advanced Access | Shown in the Connect popup |
| Subscribe a Page to our webhook | `pages_manage_metadata` | Advanced Access | This is what starts the lead delivery |
| Read Page details | `pages_read_engagement` | Advanced Access | |
| Read ad spend / results | `ads_read` | Advanced Access | Read-only; far lighter than `ads_management` |
| List the person's ad accounts | `business_management` | Advanced Access | Only to show the picker |
| **Create or edit ads** | `ads_management` | Heavy review | **Not used by XetaX** |

Two more things that are not permissions but are still gates:

- **Business verification** of your own business in Meta Business Settings. Already
  done for WhatsApp, so it does not need repeating.
- **The app must be Live**, not in Development. In Development mode only people with
  a role on the app (admin/developer/tester) can connect, and Meta delivers no
  production webhooks.

### Standard vs Advanced Access, in plain terms

*Standard Access* = works only for your own accounts and your testers. Good enough to
build and demo. *Advanced Access* = works for any business. It is what App Review
grants. Build first, file the review early, ship when it lands.

---

## 2. One-time setup in the Meta App Dashboard

The same app that already runs WhatsApp. Nothing about WhatsApp changes.

1. **Add the product "Facebook Login for Business"**, then create a **second
   configuration** (the WhatsApp one stays as it is). Ask for:
   `pages_show_list`, `pages_read_engagement`, `pages_manage_metadata`,
   `leads_retrieval`, `ads_read`, `business_management`.
   Save it and copy the **Configuration ID**.
2. **Add the product "Webhooks"** → object **Page** → subscribe the field **`leadgen`**.
   - Callback URL: `https://api.xetacrm.pro/api/public/meta/webhook`
   - Verify token: whatever you put in `META_LEADS_WEBHOOK_VERIFY_TOKEN`
     (leave that env var empty to reuse the WhatsApp token).
3. **Business Settings → Lead Ads Terms** — accept them. Meta refuses to hand over
   lead data until this is signed, and the error it returns does not say so clearly.
4. **App Review** for the six permissions above. You will need a screen recording of
   the flow (connect → a lead arriving → the report) and a public privacy-policy URL.

### Server environment

```bash
META_LEADS_CONFIG_ID=<the configuration id from step 1>
META_LEADS_WEBHOOK_VERIFY_TOKEN=<same token typed into step 2; blank = reuse WhatsApp's>
META_INSIGHTS_LOOKBACK_DAYS=7        # optional, how many days of spend to re-pull nightly
```

`META_APP_ID` and `META_APP_SECRET` are already set for WhatsApp and are reused. The
app secret is what validates the webhook signature — a wrong one shows up in the log as
`Meta page webhook rejected: bad or missing signature`.

---

## 3. What the client does

One button. **Facebook Ads → Connect Facebook**, then pick:

- the **Page** their ads run from (they must be an admin of it),
- the **ad account** (optional — only needed for spend numbers),
- the **CRM form** the leads should land in.

Field mapping is filled in automatically: Meta's standard `full_name`, `phone_number`
and `email` are matched to the form's name/phone/email fields. Anything the ad form asks
that the CRM has no field for is appended to the record's notes rather than dropped.

Finally, on the same page, pick **which stage counts as a sale** — that is what turns
the report from "cost per lead" into "cost per sale".

---

## 4. How it works once connected

1. Someone submits the lead form on Facebook or Instagram.
2. Meta POSTs a `leadgen` event to `/api/public/meta/webhook`. The signature is checked
   against `META_APP_SECRET`; anything unsigned is refused.
3. The lead's answers are fetched with the stored Page token and turned into a record.
   An existing customer with the same phone is updated instead of duplicated, and blank
   fields are filled without ever overwriting what a person typed.
4. `RECORD_CREATED` automations fire and the sales playbook takes over — so the first
   WhatsApp message can go out within seconds.
5. Which ad produced the lead is stored separately (`meta_lead_attribution`), so the
   customer's own data stays clean.
6. Every night the ad spend for the last few days is re-pulled (Meta restates recent
   days) into `meta_ad_daily`. **Update spend** on the page does it on demand.

Meta resends a webhook until it gets a 200 and can send the same lead twice at once —
both are handled: an in-flight guard plus a unique index on the lead id.

---

## 5. Troubleshooting

| Symptom | Cause |
|---|---|
| Connect popup does nothing | Ad-blocker eating `connect.facebook.net`. The page now says so and offers to retry. |
| "No Facebook Page came back" | The person is not an admin of the Page, or connected with a personal account that manages none. |
| Leads never arrive | Page not subscribed (reconnect), Lead Ads Terms not signed, or the app is still in Development mode. |
| `bad or missing signature` in the log | `META_APP_SECRET` is wrong or belongs to a different app. |
| Spend is empty | No ad account chosen, or `ads_read` not granted yet. Leads still work without it. |
| Cost per sale shows "—" | The stage that counts as a sale has not been picked yet. |

---

## 6. What is deliberately not built

- **Creating or editing ads** from the panel (`ads_management`, heavy review, and the
  client already has Ads Manager).
- **Audience or budget management.**
- **Instagram/Messenger DM as a chat channel** — a separate piece of work; this one is
  only about lead-form ads.
