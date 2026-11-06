package main

import (
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"
	"unicode"

	tg "github.com/go-telegram-bot-api/telegram-bot-api"
)

const (
	replyLifetime = 7 * 24 * time.Hour
	maxRepliesPerDay = 30
	maxReplyBytes = 1600
	replyPending = 0
	replyClaimed = 1
	replyAccepted = 2
	replyFailed = 3
)

type phoneAuth struct {
	Key string `json:"key"`
}

type phoneAck struct {
	Key string `json:"key"`
	ID int64 `json:"id"`
	Success bool `json:"success"`
}

type queuedSMS struct {
	ID int64 `json:"id"`
	Recipient string `json:"recipient"`
	Text string `json:"text"`
}

// A phone number must come from the forwarded SMS, never from a Telegram
// command or an edited message. Do not let replies target alphanumeric sender IDs,
// premium short codes or arbitrary user-provided strings.
func normalizeRecipient(sender string) (string, bool) {
	sender = strings.TrimSpace(sender)
	var number strings.Builder
	digits := 0
	for i, r := range sender {
		switch {
		case r >= '0' && r <= '9':
			number.WriteRune(r)
			digits++
		case r == '+' && i == 0:
			number.WriteRune(r)
		case r == ' ' || r == '-' || r == '(' || r == ')':
			// Common presentation separators.
		case unicode.IsSpace(r):
			// Reject unusual whitespace and control characters.
			return "", false
		default:
			return "", false
		}
	}
	return number.String(), digits >= 8 && digits <= 15
}

func keyFingerprint(key string) string {
	sum := sha256.Sum256([]byte(key))
	return hex.EncodeToString(sum[:])
}

func (w *worker) canReplyInTelegram(message *tg.Message) bool {
	if message == nil || message.Chat == nil || message.From == nil || message.ReplyToMessage == nil {
		return false
	}
	if message.Chat.Type == "private" {
		return int64(message.From.ID) == message.Chat.ID && w.userExists(message.Chat.ID)
	}
	return w.cfg.GroupID != 0 &&
		message.Chat.ID == w.cfg.GroupID &&
		int64(message.From.ID) == w.cfg.AdminID
}

// The mapping is created only after Telegram confirms delivery of the forwarded
// SMS. A Telegram reply is thus bound to a particular forwarding event and phone.
func (w *worker) enqueueTelegramReply(chatID int64, messageID, repliedToID int, body string) (string, string, error) {
	if len(body) == 0 || strings.TrimSpace(body) == "" {
		return "", "empty", nil
	}
	if len(body) > maxReplyBytes {
		return "", "too_long", nil
	}

	var ownerID int64
	var originalKeyHash, recipient, currentKey string
	err := w.db.QueryRow(
		"select f.owner_chat_id, f.key_hash, f.recipient, u.key "+
			"from forwarded_sms f join users u on u.chat_id=f.owner_chat_id "+
			"where f.telegram_chat_id=? and f.telegram_message_id=? and f.created_at>=? and u.deleted=0",
		chatID, repliedToID, time.Now().Add(-replyLifetime).Unix(),
	).Scan(&ownerID, &originalKeyHash, &recipient, &currentKey)
	if errors.Is(err, sql.ErrNoRows) {
		return "", "not_found", nil
	}
	if err != nil {
		return "", "", err
	}
	if keyFingerprint(currentKey) != originalKeyHash {
		return "", "not_found", nil
	}

	var count int
	err = w.db.QueryRow(
		"select count(*) from outgoing_replies where owner_chat_id=? and created_at>=?",
		ownerID, time.Now().Add(-24*time.Hour).Unix(),
	).Scan(&count)
	if err != nil {
		return "", "", err
	}
	if count >= maxRepliesPerDay {
		return "", "limited", nil
	}

	result, err := w.db.Exec(
		"insert or ignore into outgoing_replies "+
			"(owner_chat_id, key_hash, telegram_chat_id, telegram_message_id, recipient, body, status, created_at) "+
			"values (?, ?, ?, ?, ?, ?, ?, ?)",
		ownerID, originalKeyHash, chatID, messageID, recipient, body, replyPending, time.Now().Unix(),
	)
	if err != nil {
		return "", "", err
	}
	affected, err := result.RowsAffected()
	if err != nil {
		return "", "", err
	}
	if affected == 0 {
		return recipient, "duplicate", nil
	}
	return recipient, "queued", nil
}

func (w *worker) replyToTelegram(chatID int64, originalMessageID int, text string) {
	msg := tg.NewMessage(chatID, text)
	msg.ReplyToMessageID = originalMessageID
	_ = w.send(&messageConfig{msg})
}

func (w *worker) handleTelegramReply(message *tg.Message) {
	if !w.canReplyInTelegram(message) || message.IsCommand() {
		return
	}
	number, status, err := w.enqueueTelegramReply(
		message.Chat.ID, message.MessageID, message.ReplyToMessage.MessageID, message.Text,
	)
	if err != nil {
		lerr("cannot queue Telegram reply: %v", err)
		w.replyToTelegram(message.Chat.ID, message.MessageID, "Cannot queue SMS right now.")
		return
	}
	switch status {
	case "queued":
		w.replyToTelegram(message.Chat.ID, message.MessageID,
			fmt.Sprintf("SMS to %s queued for the connected phone. Waiting for Android.", number))
	case "duplicate":
		// Telegram may redeliver a webhook update. Never queue a second SMS.
	case "too_long":
		w.replyToTelegram(message.Chat.ID, message.MessageID, "SMS reply is too long (maximum 1600 bytes).")
	case "limited":
		w.replyToTelegram(message.Chat.ID, message.MessageID, "Daily SMS reply limit (30) reached.")
	}
}

// The phone uses the same encrypted envelope as incoming SMS. Knowledge of
// the public encryption key is insufficient: the private pairing key must also
// correspond to an active subscription.
func (w *worker) decodePhoneRequest(writer http.ResponseWriter, r *http.Request, result interface{}) error {
	var envelope smsRequest
	err := json.NewDecoder(http.MaxBytesReader(writer, r.Body, 32*1024)).Decode(&envelope)
	if err != nil {
		return err
	}
	if envelope.Version != 1 {
		return errors.New("unsupported protocol version")
	}
	plaintext, err := w.decrypt(envelope.Payload)
	if err != nil {
		return errors.New("invalid encrypted payload")
	}
	if err = json.Unmarshal(plaintext, result); err != nil {
		return errors.New("invalid payload")
	}
	return nil
}

func (w *worker) authorizePhone(key string) (int64, bool) {
	if key == "" {
		return 0, false
	}
	chat, _ := w.chatForKey(key)
	if chat == nil {
		return 0, false
	}
	return *chat, true
}

// A command is claimed before the phone receives it and is never returned
// again. This intentionally favors at-most-once SMS submission over duplicates
// if the network fails after a claim.
func (w *worker) claimReply(ownerID int64, key string) ([]queuedSMS, error) {
	result := make([]queuedSMS, 0)
	tx, err := w.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	var cmd queuedSMS
	err = tx.QueryRow(
		"select id, recipient, body from outgoing_replies "+
			"where owner_chat_id=? and key_hash=? and status=? and created_at>=? "+
			"order by id limit 1",
		ownerID, keyFingerprint(key), replyPending, time.Now().Add(-replyLifetime).Unix(),
	).Scan(&cmd.ID, &cmd.Recipient, &cmd.Text)
	if errors.Is(err, sql.ErrNoRows) {
		return result, nil
	}
	if err != nil {
		return nil, err
	}
	_, err = tx.Exec(
		"update outgoing_replies set status=?, claimed_at=? where id=? and status=?",
		replyClaimed, time.Now().Unix(), cmd.ID, replyPending,
	)
	if err != nil {
		return nil, err
	}
	if err = tx.Commit(); err != nil {
		return nil, err
	}
	return append(result, cmd), nil
}

func (w *worker) finishReply(ownerID int64, key string, id int64, success bool) (int64, int, bool, error) {
	tx, err := w.db.Begin()
	if err != nil {
		return 0, 0, false, err
	}
	defer tx.Rollback()

	var chatID int64
	var messageID int
	err = tx.QueryRow(
		"select telegram_chat_id, telegram_message_id from outgoing_replies "+
			"where id=? and owner_chat_id=? and key_hash=? and status=?",
		id, ownerID, keyFingerprint(key), replyClaimed,
	).Scan(&chatID, &messageID)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, 0, false, nil
	}
	if err != nil {
		return 0, 0, false, err
	}
	status := replyFailed
	if success {
		status = replyAccepted
	}
	_, err = tx.Exec(
		"update outgoing_replies set status=?, completed_at=? where id=?",
		status, time.Now().Unix(), id,
	)
	if err != nil {
		return 0, 0, false, err
	}
	if err = tx.Commit(); err != nil {
		return 0, 0, false, err
	}
	return chatID, messageID, true, nil
}

func (w *worker) handleReplyPoll(writer http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(writer, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req phoneAuth
	if err := w.decodePhoneRequest(writer, r, &req); err != nil {
		http.Error(writer, "invalid request", http.StatusBadRequest)
		return
	}
	ownerID, ok := w.authorizePhone(req.Key)
	if !ok {
		http.Error(writer, "unauthorized", http.StatusForbidden)
		return
	}
	commands, err := w.claimReply(ownerID, req.Key)
	if err != nil {
		lerr("cannot claim SMS reply: %v", err)
		http.Error(writer, "server error", http.StatusInternalServerError)
		return
	}
	writer.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(writer).Encode(struct {
		Result []queuedSMS `json:"result"`
	}{commands})
}

func (w *worker) handleReplyAck(writer http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(writer, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req phoneAck
	if err := w.decodePhoneRequest(writer, r, &req); err != nil || req.ID <= 0 {
		http.Error(writer, "invalid request", http.StatusBadRequest)
		return
	}
	ownerID, ok := w.authorizePhone(req.Key)
	if !ok {
		http.Error(writer, "unauthorized", http.StatusForbidden)
		return
	}
	chatID, messageID, updated, err := w.finishReply(ownerID, req.Key, req.ID, req.Success)
	if err != nil {
		lerr("cannot confirm SMS reply: %v", err)
		http.Error(writer, "server error", http.StatusInternalServerError)
		return
	}
	if updated {
		if req.Success {
			w.replyToTelegram(chatID, messageID, "SMS submitted to Android telephony. Carrier delivery is not confirmed.")
		} else {
			w.replyToTelegram(chatID, messageID, "Android could not submit this SMS. No automatic retry will occur.")
		}
	}
	writer.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(writer).Encode(map[string]string{"result": "ok"})
}

func (w *worker) cleanupReplies() {
	cutoff := time.Now().Add(-replyLifetime).Unix()
	w.mustExec("delete from forwarded_sms where created_at<?", cutoff)
	w.mustExec("delete from outgoing_replies where created_at<?", cutoff)
}
