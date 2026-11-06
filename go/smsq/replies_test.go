package main

import (
	"database/sql"
	"testing"
	"time"

	tg "github.com/go-telegram-bot-api/telegram-bot-api"
	_ "github.com/mattn/go-sqlite3"
)

func testWorker(t *testing.T) *worker {
	t.Helper()
	db, err := sql.Open("sqlite3", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	db.SetMaxOpenConns(1)
	t.Cleanup(func() { _ = db.Close() })
	w := &worker{db: db, cfg: &config{DeliveredLimit: 100, AdminID: 5, GroupID: -100444}}
	w.createDatabase()
	w.mustExec("insert into users (chat_id, key, daily_limit) values (?, ?, ?)", 5, "phone-secret", 100)
	return w
}

func TestNormalizeRecipient(t *testing.T) {
	for _, tc := range []struct{
		input, want string
		valid bool
	}{
		{"+55 (21) 98670-8379", "+5521986708379", true},
		{"21986708379", "21986708379", true},
		{"ASLAN", "", false},
		{"12345", "12345", false},
		{"+1234567890123456", "+1234567890123456", false},
		{"1234\n56789", "", false},
		{"", "", false},
	} {
		got, valid := normalizeRecipient(tc.input)
		if got != tc.want || valid != tc.valid {
			t.Errorf("normalizeRecipient(%q)=(%q,%v), want (%q,%v)", tc.input, got, valid, tc.want, tc.valid)
		}
	}
}

func TestEnqueuePollAndAckAtMostOnce(t *testing.T) {
	w := testWorker(t)
	w.mustExec("insert into forwarded_sms "+
		"(telegram_chat_id, telegram_message_id, owner_chat_id, key_hash, recipient, created_at) "+
		"values (?, ?, ?, ?, ?, ?)",
		-100444, 77, 5, keyFingerprint("phone-secret"), "+15551234567", time.Now().Unix())

	num, status, err := w.enqueueTelegramReply(-100444, 78, 77, "Hello")
	if err != nil || status != "queued" || num != "+15551234567" {
		t.Fatalf("enqueue: number=%q status=%q err=%v", num, status, err)
	}
	_, status, err = w.enqueueTelegramReply(-100444, 78, 77, "Hello")
	if err != nil || status != "duplicate" {
		t.Fatalf("duplicate was not ignored: %q %v", status, err)
	}
	cmds, err := w.claimReply(5, "phone-secret")
	if err != nil || len(cmds) != 1 || cmds[0].Text != "Hello" {
		t.Fatalf("claim: %+v %v", cmds, err)
	}
	again, err := w.claimReply(5, "phone-secret")
	if err != nil || len(again) != 0 {
		t.Fatalf("claimed command redelivered: %+v %v", again, err)
	}
	chat, message, updated, err := w.finishReply(5, "phone-secret", cmds[0].ID, true)
	if err != nil || !updated || chat != -100444 || message != 78 {
		t.Fatalf("ack: %d %d %v %v", chat, message, updated, err)
	}
	_, _, updated, err = w.finishReply(5, "phone-secret", cmds[0].ID, true)
	if err != nil || updated {
		t.Fatalf("duplicate ack should not repeat confirmation: %v %v", updated, err)
	}
}

func TestRevokeAndKeyRotationDisableOldReplies(t *testing.T) {
	w := testWorker(t)
	w.mustExec("insert into forwarded_sms "+
		"(telegram_chat_id, telegram_message_id, owner_chat_id, key_hash, recipient, created_at) "+
		"values (?, ?, ?, ?, ?, ?)",
		5, 10, 5, keyFingerprint("phone-secret"), "+15551234567", time.Now().Unix())
	w.mustExec("update users set key=? where chat_id=?", "different-secret", 5)
	_, status, err := w.enqueueTelegramReply(5, 11, 10, "No")
	if err != nil || status != "not_found" {
		t.Fatalf("re-paired messages must be rejected: %s %v", status, err)
	}
	if _, ok := w.authorizePhone("phone-secret"); ok {
		t.Fatal("old phone key authorized")
	}
	w.mustExec("update users set deleted=1 where chat_id=5")
	if _, ok := w.authorizePhone("different-secret"); ok {
		t.Fatal("revoked phone key authorized")
	}
}

func TestOnlyOwnerOrAuthorizedGroupAdminCanReply(t *testing.T) {
	w := testWorker(t)
	reply := &tg.Message{MessageID: 100}
	private := &tg.Message{
		Chat: &tg.Chat{ID: 5, Type: "private"},
		From: &tg.User{ID: 5},
		ReplyToMessage: reply,
	}
	if !w.canReplyInTelegram(private) {
		t.Fatal("paired private user rejected")
	}
	private.From.ID = 6
	if w.canReplyInTelegram(private) {
		t.Fatal("unauthorized private user allowed")
	}
	group := &tg.Message{
		Chat: &tg.Chat{ID: -100444, Type: "supergroup"},
		From: &tg.User{ID: 5},
		ReplyToMessage: reply,
	}
	if !w.canReplyInTelegram(group) {
		t.Fatal("authorized group admin rejected")
	}
	group.From.ID = 6
	if w.canReplyInTelegram(group) {
		t.Fatal("other group member allowed")
	}
	group.From.ID = 5
	group.Chat.ID = -100555
	if w.canReplyInTelegram(group) {
		t.Fatal("other Telegram group allowed")
	}
}
