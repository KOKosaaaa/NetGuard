// Package tasks runs async operations (xray deploy, agent update, ...) as
// tracked finite-state-machines with a single status row in SQLite and
// poll-able progress via /v1/tasks/{id}.
//
// Design notes:
//   - One worker goroutine per task. Workers are not pooled — these ops
//     are infrequent (minutes apart) and run sequentially per scope.
//   - We persist progress on every Step() call so a poll right after a
//     restart sees the last known state (then transitions to E_AGENT_
//     RESTARTED via DB.FailRunningTasksOnStartup at startup).
//   - The Runner func gets a *Task it can mutate via its helpers (LogF,
//     SetStep, Done, Fail). It MUST NOT touch DB directly — that's the
//     framework's job.
package tasks

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/KOKosaaaa/NetGuard/agent/internal/storage"
)

// Status string constants matching the API contract.
const (
	StatusPending  = "pending"
	StatusRunning  = "running"
	StatusDone     = "done"
	StatusFailed   = "failed"
	StatusRolled   = "rolled_back"
)

// Manager owns task lifecycle: spawn, persist, expose.
type Manager struct {
	db *storage.DB

	mu          sync.Mutex
	running     map[string]context.CancelFunc // id → cancel
	runningType map[string]string             // task type → in-flight id (single-flight)
}

func NewManager(db *storage.DB) *Manager {
	return &Manager{
		db:          db,
		running:     map[string]context.CancelFunc{},
		runningType: map[string]string{},
	}
}

// Runner is the work a task does. The framework supplies a *Handle the
// runner uses to log and announce step changes; the return value is the
// final outcome.
type Runner func(ctx context.Context, h *Handle) Outcome

// Outcome is what a Runner returns.
//
// On success: Result is the JSON payload echoed back via /v1/tasks/{id}.
// On failure: Error is the structured error; framework marks the task
// failed and (if RolledBack is true) flips status to rolled_back.
type Outcome struct {
	Result     json.RawMessage
	Error      *storage.TaskError
	RolledBack bool
}

// Spawn creates a task row + kicks off the runner in a goroutine.
// Returns the task ID immediately (caller polls /v1/tasks/{id}).
func (m *Manager) Spawn(taskType string, r Runner) (string, error) {
	// Single-flight per task type: if one of this type is already running,
	// return its id instead of spawning a duplicate. Stops two concurrent
	// /agent/provision (two xray deploys), repeated /agent/purge launching
	// multiple self-destruct scripts, etc. Callers poll the returned id.
	m.mu.Lock()
	if existing, ok := m.runningType[taskType]; ok {
		m.mu.Unlock()
		return existing, nil
	}
	id := newID()
	t := &storage.Task{
		ID:        id,
		Type:      taskType,
		Status:    StatusPending,
		Log:       []string{},
		StartedAt: time.Now(),
	}
	// Reserve the type slot before the DB write so a racing Spawn of the
	// same type can't slip through the gap.
	m.runningType[taskType] = id
	m.mu.Unlock()

	if err := m.db.InsertTask(t); err != nil {
		m.mu.Lock()
		delete(m.runningType, taskType)
		m.mu.Unlock()
		return "", err
	}
	ctx, cancel := context.WithCancel(context.Background())
	m.mu.Lock()
	m.running[id] = cancel
	m.mu.Unlock()

	go m.run(ctx, t, r)
	return id, nil
}

func (m *Manager) run(ctx context.Context, t *storage.Task, r Runner) {
	defer func() {
		m.mu.Lock()
		delete(m.running, t.ID)
		if m.runningType[t.Type] == t.ID {
			delete(m.runningType, t.Type)
		}
		m.mu.Unlock()
	}()

	t.Status = StatusRunning
	if err := m.db.UpdateTask(t); err != nil {
		log.Printf("task %s: persist running: %v", t.ID, err)
	}

	h := &Handle{t: t, db: m.db}

	// Recover panics so a buggy runner doesn't take down the agent.
	defer func() {
		if rec := recover(); rec != nil {
			h.LogF("PANIC: %v", rec)
			now := time.Now()
			t.Status = StatusFailed
			t.FinishedAt = &now
			t.Error = &storage.TaskError{
				Code: "E_INTERNAL_PANIC", Retryable: false,
				Message: fmt.Sprintf("agent crashed during task: %v", rec),
			}
			_ = m.db.UpdateTask(t)
		}
	}()

	out := r(ctx, h)

	now := time.Now()
	t.FinishedAt = &now
	switch {
	case out.Error != nil && out.RolledBack:
		t.Status = StatusRolled
		t.Error = out.Error
	case out.Error != nil:
		t.Status = StatusFailed
		t.Error = out.Error
	default:
		t.Status = StatusDone
		t.Progress = 100
		t.Result = out.Result
	}
	if err := m.db.UpdateTask(t); err != nil {
		log.Printf("task %s: persist final: %v", t.ID, err)
	}
}

// Get returns task state from DB.
func (m *Manager) Get(id string) (*storage.Task, error) {
	return m.db.GetTask(id)
}

func newID() string {
	buf := make([]byte, 8)
	_, _ = rand.Read(buf)
	return hex.EncodeToString(buf)
}

// --- Handle: the runner's API to talk back to the framework --------------

type Handle struct {
	t  *storage.Task
	db *storage.DB
}

// TaskID exposes the surrounding task's ID so a Runner can scope its
// scratch files (backup dir, temp extract dir) by it.
func (h *Handle) TaskID() string { return h.t.ID }

// LogF appends a timestamped line to the task's log and persists.
func (h *Handle) LogF(format string, args ...any) {
	line := fmt.Sprintf("[%s] %s",
		time.Now().Format("15:04:05"),
		fmt.Sprintf(format, args...))
	h.t.Log = append(h.t.Log, line)
	if err := h.db.UpdateTask(h.t); err != nil {
		log.Printf("task %s: persist log line: %v", h.t.ID, err)
	}
	log.Printf("task %s: %s", h.t.ID, line)
}

// SetStep updates step + progress. Used to give the UI a coarse
// "we're at 60% through, currently doing X" indicator.
func (h *Handle) SetStep(step string, progressPct int) {
	h.t.Step = step
	h.t.Progress = progressPct
	if err := h.db.UpdateTask(h.t); err != nil {
		log.Printf("task %s: persist step: %v", h.t.ID, err)
	}
}

// Fail returns a non-rolled-back Outcome. Use for errors that left no
// side-effects on the system (e.g. invalid input, network fail before
// any install steps).
func (h *Handle) Fail(code, msg string, retryable bool) Outcome {
	return Outcome{Error: &storage.TaskError{Code: code, Message: msg, Retryable: retryable}}
}

// FailRolledBack returns an Outcome that flips the task to rolled_back —
// use after a rollback chain succeeded and the system is back to its
// pre-task state.
func (h *Handle) FailRolledBack(code, msg string, retryable bool) Outcome {
	return Outcome{
		Error:      &storage.TaskError{Code: code, Message: msg, Retryable: retryable},
		RolledBack: true,
	}
}

// Ok returns a success Outcome with a JSON-marshaled payload.
func (h *Handle) Ok(result any) Outcome {
	b, err := json.Marshal(result)
	if err != nil {
		return h.Fail("E_INTERNAL", "marshal result: "+err.Error(), false)
	}
	return Outcome{Result: b}
}
