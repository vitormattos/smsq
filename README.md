Android app forwarding SMS messages to Telegram bot
===================================================

[![license: GPL v3](https://img.shields.io/badge/license-GPL_v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![github pages](https://img.shields.io/badge/github-pages-blue.svg)](https://smsq.me)

It is just a handy thing when you work on your laptop.
You probably don't want to pick up your phone every time you need to enter an OTP code.

## This is parametrized fork of original smsq by Igor Mikushkin

[Original repository](https://github.com/igrmk/smsq)

Since Igor's backend doesn't seem to work anymore, I decided to set up my own.

This fork allows you to set up your own backend and telegram bot.

![image](https://github.com/tarasverq/smsq/assets/8226275/9d411f25-f668-4f14-a33e-df06a3882346)

### Backend installation (optional)

For convenience you can use docker image `tarasverq/smsq-backend`

Just run it with command <br/>
`docker run --name smsq -d --env=DOMAIN=<DOMAIN_HERE> --env=BOT_TOKEN=<BOT_TOKEN_HERE> --env=ADMIN_ID=<YOUR_TG_ID_HERE> -p <PORT_HERE>:80 tarasverq/smsq-backend:latest`

<br/>

| Variable name   | Description                                                                               |
|-----------------|-------------------------------------------------------------------------------------------|
| DOMAIN_HERE     | Domain with port for tg webhook. Webhook can be set up only on ports 80, 88, 443 or 8443  |
| BOT_TOKEN_HERE  | Your bot token obtained from [@BotFather](https://t.me/BotFather)                         |
| YOUR_TG_ID_HERE | Your TG ID. Easiest way to get it: [@username_to_id_bot](https://t.me/username_to_id_bot) |
| GROUP_ID (optional) | Group ID to send SMS messages to. If set, messages will be sent to this group instead of individual chat. Get it by adding [@username_to_id_bot](https://t.me/username_to_id_bot) to your group |
| PORT_HERE       | Host Machine port                                                                         |


Example:<br/>
`
docker run --name smsq -d --env=DOMAIN=domain.com:443 --env=BOT_TOKEN=6531881811:AAF111e3coTifgug03-MdxN2tUEh7kp4Sm4 --env=ADMIN_ID=123321 -p 8888:80 tarasverq/smsq-backend:latest
`

#### Sending messages to a Telegram group (optional)

Add `GROUP_ID` environment variable with your group ID:

```bash
docker run --name smsq -d \
  --env=DOMAIN=domain.com:443 \
  --env=BOT_TOKEN=your_bot_token \
  --env=ADMIN_ID=123321 \
  --env=GROUP_ID=-1001234567890 \
  -p 8080:80 \
  ghcr.io/vitormattos/smsq:latest
```

Get group ID by adding [@username_to_id_bot](https://t.me/username_to_id_bot) to your group (will be negative, e.g., `-1001234567890`)

**IMPORTANT:** Don't forget to send `/start` message to your bot before starting up docker image

In case you want to use https (recommended) set up nginx reverse proxy with letsencrypt certs.
<br/>[Manual](https://gist.github.com/gmolveau/5e5b0bd2773100d85d9302d0fa96632d)

<details>
  <summary>Example nginx conf matching with ports from command above.</summary>

```
server {
    server_name   domain.com;
    location / {
        proxy_pass         http://127.0.0.1:8888;
        proxy_http_version 1.1;
        proxy_set_header   Upgrade $http_upgrade;
        proxy_set_header   Connection keep-alive;
        proxy_set_header   Host $host;
        proxy_cache_bypass $http_upgrade;
        proxy_set_header   X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
    }

    listen 443 ssl; # managed by Certbot
    ssl_certificate /etc/letsencrypt/live/domain.com/fullchain.pem; # managed by Certbot
    ssl_certificate_key /etc/letsencrypt/live/domain.com/privkey.pem; # managed by Certbot
    include /etc/letsencrypt/options-ssl-nginx.conf; # managed by Certbot
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem; # managed by Certbot
}
```
</details>

Installation client
------------
Just install mobile app from [releases](https://github.com/tarasverq/smsq/releases) 

> :warning: **WARNING**
>
> If you want to use it into long-running background mode, you need to disable Google Play Protect. [Instruction](https://support.mobile-tracker-free.com/hc/en-us/articles/360005346953-How-to-disable-Google-Play-Protect-) <br/>
> Otherwise, the Google Play Protect service will unload the app every 4 hours because it doesn't have a certification.<br/>
> Certification requires an android developer account, which I don't have.
>
> ```
> Finsky  : [849] afwq.a(9): Attempting to stop application: com.github.igrmk.smsq
> ActivityManager: Force stopping com.github.igrmk.smsq appid=10459 user=0: from pid 25542
> Finsky  : [849] afyu.a(861): Unwanted app (MUwS) without is_muws flag was returned with verdict POTENTIALLY_UNWANTED on autoscan: com.github.igrmk.smsq
> ```

Usage
-----

![image](https://github.com/tarasverq/smsq/assets/8226275/9d411f25-f668-4f14-a33e-df06a3882346)

1. Install Android app
2. Open the app
3. Put your bot name to first text field. e.g. `my_sms_bot` 
4. Put your backend url **with ending slash**.  e.g. `https://domain.com/` 
5. start forwarding, connect Telegram
6. Now you receive your SMS messages in this bot!

### In case you don't want to set up backend you can use backend deployed by me.

Bot: `sms_j_bot`<br/>
Backend URL: `https://smsq.jora.wtf/`


## Reply to SMS from Telegram

This is an **opt-in** feature and requires updating **both** the self-hosted
backend and the Android application. It is disabled by default.

1. Configure an **HTTPS** backend URL, pair the phone with your Telegram bot and start SMS forwarding.
2. Enable **Allow SMS replies** in the Android application and grant the Android
   \`SEND_SMS\` permission. The application will display an ongoing notification
   while its foreground service checks the backend approximately once a minute.
3. In Telegram, use **Reply** on an SMS forwarded **after upgrading the backend** and type your response. Previously forwarded Telegram messages do not have a stored recipient mapping and cannot be answered through this feature.
   The bot queues the text for the number that sent that specific SMS. It cannot
   be redirected by editing the quoted message or typing a different number.
4. The bot first confirms that the reply was queued, then reports whether Android
   accepted the submission to the SMS subsystem. This **does not confirm carrier
   delivery**. Mobile operator SMS fees may apply.

Only the paired user can reply in a private chat. When \`GROUP_ID\` is configured,
**only the Telegram account identified by \`ADMIN_ID\`** may initiate SMS replies
from that group. Other group members cannot send SMS. Replies to unrelated bot
messages, expired forwarded messages (older than seven days), invalid/short-code
senders and empty messages are ignored or rejected. A phone can queue up to 30
outgoing replies per 24 hours. Reply text has a 1,600-byte limit.

For safety, an outgoing command is handed out to the phone **at most once**.
If the connection is lost during that hand-off, the SMS might not be sent;
the system does not automatically send it again. The phone stores acknowledgements
locally to retry reporting submission status without resending the SMS.

The background service runs only while forwarding and SMS replies are enabled.
Modern Android battery management may delay the one-minute checks; there is no
instant-push guarantee. The Android SMS subsystem uses the phone's **default
outgoing SIM**; with multiple SIMs, check the default before enabling this feature.

## Show contact names

Enable **Show contact names** and grant \`READ_CONTACTS\`. If the sender's
number matches an entry in the phone's address book, a forwarded message shows
\`Contact name (+15551234567)\` instead of just the number. The original
sender number remains visible, and contacts without a match still show only
their number. This switch is independent of enabling SMS replies.

### Release and upgrade notes

- Deploy the updated backend first. Its SQLite schema migration creates
  short-lived forwarding references and outgoing SMS command records.
- Install the updated Android app (v1.7.0 / version code 17), open it and
  explicitly grant the new permissions. Existing queued incoming SMS are
  preserved by a database migration.
- There are no new backend environment variables; existing \`DOMAIN\`,
  \`BOT_TOKEN\`, \`ADMIN_ID\`, and optional \`GROUP_ID\` are used.
- The previous Android app remains able to forward incoming SMS. Its users
  do not gain the SMS sending capability without installing and opting in
  to the new version.

Privacy policy
--------------
__[PRIVACY POLICY](PRIVACY.md)__

Thanks to
---------
- [Igor Mikushkin](https://github.com/igrmk)

- [JetBrains](https://www.jetbrains.com/?from=smsq)

