package main

import (
	"database/sql"
)

var migrations = []func(w *worker){
	func(w *worker) {
		w.mustExec(`
			create table if not exists feedback (
				chat_id integer,
				text text);`)
		w.mustExec(`
			create table if not exists users (
				chat_id integer primary key,
				key text not null default '',
				delivered integer not null default 0,
				delivered_today integer not null default 0,
				received_today integer not null default 0,
				deleted integer not null default 0);`)
		w.mustExec(`
			create table if not exists midnight (
				unix_time integer not null default 0);`)
	},
	func(w *worker) {
		w.mustExec("alter table users add daily_limit integer not null default 0;")
		w.mustExec("update users set daily_limit=?", w.cfg.DeliveredLimit)
	},
	func(w *worker) {
		w.mustExec("create table if not exists forwarded_sms ("+
			"telegram_chat_id integer not null, telegram_message_id integer not null, "+
			"owner_chat_id integer not null, key_hash text not null, recipient text not null, "+
			"created_at integer not null, primary key(telegram_chat_id, telegram_message_id))")
		w.mustExec("create table if not exists outgoing_replies ("+
			"id integer primary key autoincrement, owner_chat_id integer not null, "+
			"key_hash text not null, telegram_chat_id integer not null, "+
			"telegram_message_id integer not null, recipient text not null, body text not null, "+
			"status integer not null default 0, created_at integer not null, "+
			"claimed_at integer, completed_at integer, "+
			"unique(telegram_chat_id, telegram_message_id))")
		w.mustExec("create index if not exists outgoing_replies_poll on outgoing_replies(owner_chat_id, key_hash, status, id)")
	},
}

func (w *worker) applyMigrations() {
	row := w.db.QueryRow("select version from schema_version")
	var version int
	err := row.Scan(&version)
	if err == sql.ErrNoRows {
		version = -1
		w.mustExec("insert into schema_version(version) values (0)")
	} else {
		checkErr(err)
	}
	for i, m := range migrations[version+1:] {
		n := i + version + 1
		linf("applying migration %d", n)
		m(w)
		w.mustExec("update schema_version set version=?", n)
	}
}

func (w *worker) createDatabase() {
	linf("creating database if needed...")
	w.mustExec(`create table if not exists schema_version (version integer);`)
	w.applyMigrations()
}
