PRIVACY POLICY
==============

smsQ forwards SMS messages from an Android device to a Telegram bot. It
processes the sender's number or sender ID, message text, time and timezone,
the Telegram chat identifier, and optionally the SIM/carrier information.

Optional contact lookup
-----------------------
If you choose "Show contact names" and grant Android's READ_CONTACTS
permission, smsQ looks up the sender number on the device and includes the
matching display name (along with the original number) in the forwarded
Telegram message. The full address book is not uploaded.

Optional outgoing SMS
---------------------
If you choose "Allow SMS replies" and grant Android's SEND_SMS permission,
replying to a forwarded message in Telegram can cause the phone to send a
real SMS to its original sender. This may incur mobile operator charges.
A foreground service checks for commands approximately every minute.
You can disable this feature in the Android app at any time.

Data handled by the self-hosted backend
---------------------------------------
The backend retains Telegram chat IDs, pairing keys, delivery counters and
subscription state. It decrypts incoming SMS messages in memory for forwarding
to Telegram; incoming message bodies are not stored in its SQLite database.

To support optional replies, the backend stores Telegram message identifiers,
sender phone numbers and an irreversible fingerprint of the paired phone key,
as well as the text and destination of queued outgoing SMS. These records
are kept for a maximum of approximately seven days, with cleanup performed
when the daily maintenance cycle runs. Outgoing texts are also available
to the Telegram service as ordinary messages.

The Android app temporarily stores received SMS and optional contact names
until forwarding succeeds. When outgoing SMS submission is attempted, the
phone locally stores only command identifiers and submission acknowledgements
to retry reporting their status; it does not automatically resend an outgoing
SMS command.

Security and recipients
-----------------------
Incoming SMS payloads and outgoing-command requests use hybrid encryption
to the self-hosted backend. HTTPS is required for the optional SMS reply
feature to protect the backend responses. Private Telegram replies require
the paired account; group replies are restricted to the configured
administrator's Telegram account. The backend does not accept arbitrary
recipient numbers typed into Telegram commands.

Messages sent to Telegram are subject to Telegram's own privacy policy.
The self-hosted operator controls the backend database and retention.
No transmission or storage system can guarantee absolute security.

LOG DATA
--------
On errors the application may log device or software information, error
types, service events and network metadata. SMS bodies and contact names
are not intentionally included in routine application logs.

CHANGES TO THIS POLICY
----------------------
Updates are published at https://smsq.me/PRIVACY.html.
