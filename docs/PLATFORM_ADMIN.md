# Platform console — XetaX's own back office

Internal document. This is the screen the XetaX team uses to see every customer
workspace, change plans, hand out AI credits and switch an account off. Customers
never see it: the sidebar entry is hidden and the API refuses anyone without the
platform flag, whatever their role inside their own workspace says.

## Getting the first admin

Put the email on the server and restart:

```bash
PLATFORM_ADMIN_EMAILS=punit@xetacrm.pro,ops@xetacrm.pro
```

On every boot those accounts are promoted and their own workspace is moved to the
**PLATFORM** plan. The account must already exist — register normally first.

Removing an email from the list does **not** demote anyone. That is deliberate: a
typo in an env var should never lock the team out of its own console. To actually
remove someone, use `POST /api/platform/users/{userId}/platform-admin` with
`{"value": false}` — and you cannot remove your own access.

## What the PLATFORM plan means

- **Never expires.** It has no subscription row, so the nightly sweep that expires
  paid plans has nothing to act on, and it is not a trial, so it is never downgraded.
- **Cannot be disabled** from the console, by anyone including yourself.
- **AI is still metered.** Quotas are set high enough never to block, but every
  assistant and agent message is still counted — so the only thing the team's own
  usage costs is the AI tokens it actually burns.

## The console

**Sidebar → Platform.**

Top row: how many workspaces exist, how many are paying, total booked amount, how
many expire within a fortnight, team members across the platform, and AI messages
this month.

The table lists every workspace with its plan, team size, record count, AI used and
days remaining. Search matches name, email, company or phone; the dropdown filters
by plan.

**Manage** opens a drawer with two levers:

- **Set the plan** — pick the plan, months, the amount actually received and a
  payment reference. This is the same code path as the internal billing API, so the
  workspace's quotas change immediately and the payment lands in its history.
  The amount pre-fills at list price for the months chosen; type over it for a discount.
- **AI credits** — add top-up messages that never expire and are shared by the
  assistant and the agents. A negative number takes credits back.

Below that: the team on the account, the full payment history, and a button to
**disable** the workspace. Disabling switches off the owner and every member, so
nobody can sign in; their data is untouched and enabling restores everything.

## What it deliberately cannot do

- **Open a customer's records, chats or documents.** It counts them, it does not
  read them. If support genuinely needs to see inside an account, ask the customer
  to add a member.
- **Change a customer's data, forms or automations.**
- **Delete a workspace.** Disable is reversible; deletion is not, so it stays a
  database job done by hand, deliberately.

## API, if you need it without the screen

Everything is under `/api/platform`, with a normal login token from a platform
admin account:

| Method | Path | Does |
|---|---|---|
| GET | `/me` | is this account a platform admin? |
| GET | `/overview` | the counters at the top |
| GET | `/workspaces?q=&plan=` | the table |
| GET | `/workspaces/{ownerUserId}` | one workspace, with team and payment history |
| POST | `/workspaces/{ownerUserId}/plan` | `{planKey, months, amountRupees, paymentRef, note}` |
| POST | `/workspaces/{ownerUserId}/ai-credit` | `{messages: 1000}` |
| POST | `/workspaces/{ownerUserId}/status` | `{enabled: false}` |
| POST | `/users/{userId}/platform-admin` | `{value: true}` |

The older `/api/internal/billing` endpoints still work and still need
`INTERNAL_ADMIN_KEY`; the console is the same thing with a screen in front of it.
