// Package storage migrates from JSON to SQLite once concurrent writers
// appear (task workers + auth-rotate goroutines). modernc.org/sqlite is
// pure-Go so we keep CGO_ENABLED=0.
//
// Schema lives inline below — small enough that a separate migration
// runner is overkill. Each CREATE is IF NOT EXISTS so a fresh agent and
// an upgraded one converge on the same shape.
package storage

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

// DB wraps *sql.DB with helpers for the structures we persist.
//
// Callers should treat the *sql.DB inside as private — go through the
// methods so we get consistent error wrapping and don't accidentally hold
// connections during long ops.
type DB struct {
	db *sql.DB
	mu sync.Mutex // guards in-process atomicity for non-trivial sequences
}

// Open creates or opens the agent's SQLite DB at path.
// Caller must Close().
func Open(path string) (*DB, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, err
	}
	// Busy timeout 5s — under our load contention should be rare, but a
	// brief wait beats EBUSY back to the caller. WAL is the standard
	// choice for concurrent readers + a single writer.
	dsn := fmt.Sprintf("file:%s?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)", path)
	sqlDB, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("sql.Open: %w", err)
	}
	if err := sqlDB.Ping(); err != nil {
		_ = sqlDB.Close()
		return nil, fmt.Errorf("ping: %w", err)
	}
	d := &DB{db: sqlDB}
	if err := d.migrate(); err != nil {
		_ = sqlDB.Close()
		return nil, err
	}
	return d, nil
}

func (d *DB) Close() error { return d.db.Close() }

func (d *DB) migrate() error {
	stmts := []string{
		// kv holds the singleton pair-token + any future small scalars.
		// We keep it in SQL instead of a separate file so all state lives
		// in one place (one fsync, one backup).
		`CREATE TABLE IF NOT EXISTS kv (
			k TEXT PRIMARY KEY,
			v TEXT NOT NULL
		)`,
		// bearers: long-lived per-device tokens issued by /v1/auth/pair.
		// last_seen_at is bumped on every Verify so we can show an
		// activity column in the Android UI.
		`CREATE TABLE IF NOT EXISTS bearers (
			token        TEXT PRIMARY KEY,
			device_name  TEXT NOT NULL,
			app_version  TEXT NOT NULL,
			created_at   TEXT NOT NULL,
			expires_at   TEXT NOT NULL,
			last_seen_at TEXT NOT NULL,
			revoked_at   TEXT
		)`,
		// tasks: async operations like xray deploy / agent self-update.
		// log + result + error are JSON blobs — they grow over the
		// lifetime of a task but never need to be queried with WHERE.
		`CREATE TABLE IF NOT EXISTS tasks (
			id          TEXT PRIMARY KEY,
			type        TEXT NOT NULL,
			status      TEXT NOT NULL,
			step        TEXT NOT NULL DEFAULT '',
			progress    INTEGER NOT NULL DEFAULT 0,
			log_json    TEXT NOT NULL DEFAULT '[]',
			result_json TEXT,
			error_json  TEXT,
			started_at  TEXT NOT NULL,
			finished_at TEXT
		)`,
		`CREATE INDEX IF NOT EXISTS tasks_status_idx ON tasks(status)`,
		// xray_inbounds: profiles we've materialized into the running
		// /etc/xray/config.json. inbound_id is what the Android client
		// uses for DELETE /v1/xray/profile/{id}.
		`CREATE TABLE IF NOT EXISTS xray_inbounds (
			inbound_id  TEXT PRIMARY KEY,
			protocol    TEXT NOT NULL,
			port        INTEGER NOT NULL,
			config_json TEXT NOT NULL,
			profile_uri TEXT NOT NULL,
			created_at  TEXT NOT NULL
		)`,
	}
	for _, s := range stmts {
		if _, err := d.db.Exec(s); err != nil {
			return fmt.Errorf("migrate: %s: %w", firstLine(s), err)
		}
	}
	return nil
}

func firstLine(s string) string {
	for i, c := range s {
		if c == '\n' {
			return s[:i]
		}
	}
	return s
}

// --- kv helpers -----------------------------------------------------------

func (d *DB) KVGet(key string) (string, bool, error) {
	var v string
	err := d.db.QueryRow(`SELECT v FROM kv WHERE k=?`, key).Scan(&v)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		return "", false, nil
	case err != nil:
		return "", false, err
	}
	return v, true, nil
}

func (d *DB) KVPut(key, value string) error {
	_, err := d.db.Exec(`INSERT INTO kv(k,v) VALUES(?,?)
		ON CONFLICT(k) DO UPDATE SET v=excluded.v`, key, value)
	return err
}

func (d *DB) KVDelete(key string) error {
	_, err := d.db.Exec(`DELETE FROM kv WHERE k=?`, key)
	return err
}

// --- bearers --------------------------------------------------------------

type Bearer struct {
	Token      string    `json:"-"` // never echo to API
	DeviceName string    `json:"device_name"`
	AppVersion string    `json:"app_version"`
	CreatedAt  time.Time `json:"created_at"`
	ExpiresAt  time.Time `json:"expires_at"`
	LastSeenAt time.Time `json:"last_seen_at"`
}

func (d *DB) InsertBearer(b *Bearer) error {
	_, err := d.db.Exec(
		`INSERT INTO bearers(token,device_name,app_version,created_at,expires_at,last_seen_at)
		 VALUES(?,?,?,?,?,?)`,
		b.Token, b.DeviceName, b.AppVersion,
		b.CreatedAt.Format(time.RFC3339Nano),
		b.ExpiresAt.Format(time.RFC3339Nano),
		b.LastSeenAt.Format(time.RFC3339Nano),
	)
	return err
}

// LookupBearer returns the bearer if it's known, unexpired, not revoked.
// Side-effect: bumps last_seen_at to now.
func (d *DB) LookupBearer(token string) (*Bearer, error) {
	row := d.db.QueryRow(
		`SELECT token,device_name,app_version,created_at,expires_at,last_seen_at
		 FROM bearers WHERE token=? AND revoked_at IS NULL`, token)
	b := &Bearer{}
	var created, expires, seen string
	if err := row.Scan(&b.Token, &b.DeviceName, &b.AppVersion, &created, &expires, &seen); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	b.CreatedAt, _ = time.Parse(time.RFC3339Nano, created)
	b.ExpiresAt, _ = time.Parse(time.RFC3339Nano, expires)
	b.LastSeenAt, _ = time.Parse(time.RFC3339Nano, seen)
	if time.Now().After(b.ExpiresAt) {
		return nil, ErrExpired
	}
	now := time.Now()
	_, _ = d.db.Exec(`UPDATE bearers SET last_seen_at=? WHERE token=?`,
		now.Format(time.RFC3339Nano), token)
	b.LastSeenAt = now
	return b, nil
}

// RevokeBearer marks a token as revoked; subsequent Lookups fail.
func (d *DB) RevokeBearer(token string) error {
	_, err := d.db.Exec(`UPDATE bearers SET revoked_at=? WHERE token=?`,
		time.Now().Format(time.RFC3339Nano), token)
	return err
}

// --- tasks ----------------------------------------------------------------

type Task struct {
	ID         string          `json:"task_id"`
	Type       string          `json:"type"`
	Status     string          `json:"status"`
	Step       string          `json:"step"`
	Progress   int             `json:"progress"`
	Log        []string        `json:"log"`
	Result     json.RawMessage `json:"result,omitempty"`
	Error      *TaskError      `json:"error,omitempty"`
	StartedAt  time.Time       `json:"started_at"`
	FinishedAt *time.Time      `json:"finished_at,omitempty"`
}

type TaskError struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	Retryable bool   `json:"retryable"`
}

func (d *DB) InsertTask(t *Task) error {
	logJSON, _ := json.Marshal(t.Log)
	_, err := d.db.Exec(
		`INSERT INTO tasks(id,type,status,step,progress,log_json,started_at)
		 VALUES(?,?,?,?,?,?,?)`,
		t.ID, t.Type, t.Status, t.Step, t.Progress,
		string(logJSON), t.StartedAt.Format(time.RFC3339Nano),
	)
	return err
}

// UpdateTask persists the mutable fields of t. The lock keeps appendLog
// + UpdateTask race-free at the FSM layer.
func (d *DB) UpdateTask(t *Task) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	logJSON, _ := json.Marshal(t.Log)
	var resultJSON, errorJSON, finishedAt any
	if t.Result != nil {
		resultJSON = string(t.Result)
	}
	if t.Error != nil {
		b, _ := json.Marshal(t.Error)
		errorJSON = string(b)
	}
	if t.FinishedAt != nil {
		finishedAt = t.FinishedAt.Format(time.RFC3339Nano)
	}
	_, err := d.db.Exec(
		`UPDATE tasks
		 SET status=?, step=?, progress=?, log_json=?,
		     result_json=?, error_json=?, finished_at=?
		 WHERE id=?`,
		t.Status, t.Step, t.Progress, string(logJSON),
		resultJSON, errorJSON, finishedAt, t.ID,
	)
	return err
}

func (d *DB) GetTask(id string) (*Task, error) {
	row := d.db.QueryRow(
		`SELECT id,type,status,step,progress,log_json,
		        result_json,error_json,started_at,finished_at
		 FROM tasks WHERE id=?`, id)
	t := &Task{}
	var logJSON, startedAt string
	var resultJSON, errorJSON, finishedAt sql.NullString
	if err := row.Scan(&t.ID, &t.Type, &t.Status, &t.Step, &t.Progress,
		&logJSON, &resultJSON, &errorJSON, &startedAt, &finishedAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	_ = json.Unmarshal([]byte(logJSON), &t.Log)
	if resultJSON.Valid {
		t.Result = json.RawMessage(resultJSON.String)
	}
	if errorJSON.Valid {
		t.Error = &TaskError{}
		_ = json.Unmarshal([]byte(errorJSON.String), t.Error)
	}
	t.StartedAt, _ = time.Parse(time.RFC3339Nano, startedAt)
	if finishedAt.Valid {
		parsed, _ := time.Parse(time.RFC3339Nano, finishedAt.String)
		t.FinishedAt = &parsed
	}
	return t, nil
}

// FailRunningTasksOnStartup marks any task left in pending/running by a
// previous agent crash as failed. We do NOT auto-resume — too many
// half-finished states to reason about; the user just retries from the UI.
func (d *DB) FailRunningTasksOnStartup() (int, error) {
	now := time.Now().Format(time.RFC3339Nano)
	errBlob, _ := json.Marshal(&TaskError{
		Code: "E_AGENT_RESTARTED", Retryable: true,
		Message: "task was interrupted by an agent restart; retry from the UI",
	})
	res, err := d.db.Exec(
		`UPDATE tasks SET status='failed', error_json=?, finished_at=?
		 WHERE status IN ('pending','running')`,
		string(errBlob), now)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	return int(n), nil
}

// --- xray inbounds --------------------------------------------------------

type XrayInbound struct {
	ID         string
	Protocol   string
	Port       int
	ConfigJSON string
	ProfileURI string
	CreatedAt  time.Time
}

func (d *DB) InsertXrayInbound(x *XrayInbound) error {
	_, err := d.db.Exec(
		`INSERT INTO xray_inbounds(inbound_id,protocol,port,config_json,profile_uri,created_at)
		 VALUES(?,?,?,?,?,?)`,
		x.ID, x.Protocol, x.Port, x.ConfigJSON, x.ProfileURI,
		x.CreatedAt.Format(time.RFC3339Nano),
	)
	return err
}

func (d *DB) ListXrayInbounds() ([]*XrayInbound, error) {
	rows, err := d.db.Query(
		`SELECT inbound_id,protocol,port,config_json,profile_uri,created_at
		 FROM xray_inbounds ORDER BY created_at`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*XrayInbound
	for rows.Next() {
		x := &XrayInbound{}
		var created string
		if err := rows.Scan(&x.ID, &x.Protocol, &x.Port, &x.ConfigJSON, &x.ProfileURI, &created); err != nil {
			return nil, err
		}
		x.CreatedAt, _ = time.Parse(time.RFC3339Nano, created)
		out = append(out, x)
	}
	return out, rows.Err()
}

func (d *DB) DeleteXrayInbound(id string) error {
	_, err := d.db.Exec(`DELETE FROM xray_inbounds WHERE inbound_id=?`, id)
	return err
}

// --- errors ---------------------------------------------------------------

var (
	ErrNotFound = errors.New("not found")
	ErrExpired  = errors.New("expired")
)
