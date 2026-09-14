# Meta App Review — screencast script

Meta asks for one screencast that proves two permissions separately. This is
the shot list for that recording, plus the setup that has to be right before
the camera rolls.

Record **one continuous take**, no cuts, no speed-ups, no edited-in overlays.
Reviewers reject edited footage. If a step goes wrong, start the recording
again from the top.

---

## What each permission has to show

| Permission | What must be on screen |
|---|---|
| `whatsapp_business_messaging` | A message sent from XetaX CRM to a real WhatsApp number, **and** that number's WhatsApp screen receiving it |
| `whatsapp_business_management` | A message template created from XetaX CRM, appearing with its Meta status |

---

## Before you record

1. **Deploy the current backend.** The record page's WhatsApp send was
   returning `400 phoneFieldKey is required` and is fixed in this branch but
   not yet on production. Recording before the deploy means the send fails on
   camera.
2. **Have two screens in one frame.** The laptop showing XetaX CRM, and the
   phone showing WhatsApp. Either point the laptop camera at the phone, mirror
   the phone to the desktop (scrcpy, Phone Link, QuickTime), or open
   web.whatsapp.com in a second browser window. One recording must contain
   both — Meta will not accept two separate files.
3. **The receiving number must not be the business number itself.** Use a
   second personal phone.
4. **Open the 24-hour window first, off camera.** Send any message from the
   receiving phone to your business number before recording. Without it a
   free-text send is refused and you would have to use a template.
5. **Pick a template name you have not used.** Meta rejects a duplicate name,
   and a rejection on camera looks like a broken feature. Something like
   `order_update_sep14`.
6. **Clean the browser.** Close unrelated tabs, hide bookmarks, no personal
   data in view. Keep the address bar visible the whole time — it proves the
   footage is your platform.
7. **Screen recorder:** OBS Studio, or `Ctrl+Alt+Shift+R` on GNOME. 1080p.
   Narration is optional; if you do not speak, the on-screen actions have to
   be slow enough to follow.

---

## The take

### Part 1 — who you are (about 20 seconds)

1. Start on the XetaX CRM login page with `app.xetacrm.pro` readable in the
   address bar.
2. Log in with the account whose WhatsApp number is connected.
3. Land on the dashboard. Pause two seconds so the reviewer sees the product.

### Part 2 — `whatsapp_business_management`, creating a template (about 90 seconds)

4. Sidebar → **WhatsApp** → the settings page. Show the connected number and
   its status near the top.
5. Scroll to **Message templates**. Pause on the existing list so the
   reviewer sees name, language, category and status columns.
6. Click **New template**.
7. Fill the form slowly, letting each field register on camera:
   - Template name: `order_update_sep14`
   - Category: **Utility** (approves fastest, often within minutes)
   - Language: English (en)
   - Header: `Order update`
   - Body: `Hi {{1}}, your order {{2}} has been shipped.`
   - Example values: `Punit, ORD-1042`
   - Footer: `XetaX CRM`
8. Submit. Wait on screen for the row to appear in the templates table with
   status **PENDING**.
9. Click **Sync** once. If Meta has already approved it, the status flips to
   **APPROVED** on camera, which is the strongest possible proof. If it is
   still pending, that is fine — the creation is what the permission covers.

> This part proves the platform calls `POST /{waba-id}/message_templates` on
> the Graph API. Do not create the template in Meta Business Manager — it has
> to be created from XetaX CRM.

### Part 3 — `whatsapp_business_messaging`, sending and receiving (about 90 seconds)

10. Bring the phone into frame. Show WhatsApp open on the chat with your
    business number, with no new message yet. Hold it for three seconds so the
    reviewer sees the "before" state.
11. Back on the laptop: sidebar → **WhatsApp** → **Inbox**. Open the
    conversation with that number. (Or open the customer's record and use the
    WhatsApp panel on the record page — either is valid.)
12. Type a message that is obviously written live, so it cannot look staged.
    Include the date and something unique:
    `XetaX CRM test — 14 Sep, order ORD-1042 shipped.`
13. Click send. Show the message appearing in the XetaX thread with its
    delivery tick.
14. **Without cutting**, move to the phone and show the same message arriving
    in WhatsApp. Let the notification and the message body both be visible and
    readable.
15. Reply from the phone with something short, like `received`.
16. Move back to the laptop and show that reply landing in the XetaX inbox on
    its own, without a page refresh.

Step 16 is worth including even though Meta does not ask for it. It shows the
integration is two-way and live, which usually settles any doubt about whether
the platform is really connected.

### Part 4 — close (about 10 seconds)

17. Stay on the inbox for a few seconds with both the sent and received
    messages visible, then stop the recording.

---

## What gets videos rejected

- Two separate files, one for the platform and one for the phone. It must be
  one recording containing both.
- Cuts or jumps, which read as hiding a failure.
- The receiving screen never shown, only the platform's own "sent" state.
- A template created in Meta Business Manager rather than in the platform.
- The address bar hidden, so the reviewer cannot tell whose platform it is.
- Text too small to read at the resolution you upload.
- A message so generic it could be a mock-up. Date plus a unique reference
  avoids this.

---

## What to write in the submission form

Keep it matched to the timestamps.

> XetaX CRM is a CRM where businesses manage leads and talk to their customers
> on WhatsApp using their own WhatsApp Business number.
>
> `whatsapp_business_management` — from 0:20 the video shows a user creating a
> message template inside XetaX CRM. The platform submits it to the Graph API
> at `/{waba-id}/message_templates` and shows the returned status in the
> templates list.
>
> `whatsapp_business_messaging` — from 1:50 the video shows a user sending a
> WhatsApp message to a customer from the XetaX CRM inbox, the same message
> arriving on the customer's phone, and the customer's reply arriving back in
> the inbox.

---

## After recording

Upload as MP4, 1080p, under Meta's size limit. If the file is too large:

```
ffmpeg -i recording.mp4 -vcodec libx264 -crf 28 -preset slow -acodec aac review.mp4
```
