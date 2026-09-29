package main

import (
	"fmt"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"sync"
	"time"
)

type UpstreamConfig struct {
	ID          string `json:"id"`
	Address     string `json:"address"`
	Concurrency int    `json:"concurrency"`
}

type NodeStatus struct {
	Generation  int    `json:"generation"`
	ID          string `json:"id"`
	Address     string `json:"address"`
	Concurrency int    `json:"concurrency"`
	InFlight    int    `json:"in_flight"`
	Draining    bool   `json:"draining"`
}

type Snapshot struct {
	Version  int          `json:"version"`
	Current  []NodeStatus `json:"current"`
	Draining []NodeStatus `json:"draining"`
}

type Node struct {
	generation int
	id         string
	address    string
	limit      int
	inFlight   int
	draining   bool
	transport  *http.Transport
	wg         sync.WaitGroup
}

func newNode(generation int, cfg UpstreamConfig) *Node {
	return &Node{
		generation: generation,
		id:         cfg.ID,
		address:    cfg.Address,
		limit:      cfg.Concurrency,
		transport:  http.DefaultTransport.(*http.Transport).Clone(),
	}
}

func (n *Node) status() NodeStatus {
	return NodeStatus{
		Generation:  n.generation,
		ID:          n.id,
		Address:     n.address,
		Concurrency: n.limit,
		InFlight:    n.inFlight,
		Draining:    n.draining,
	}
}

func (n *Node) wait() { n.wg.Wait() }

type Manager struct {
	mu       sync.Mutex
	version  int
	nextGen  int
	current  map[string]*Node
	draining []*Node
}

func NewManager() *Manager {
	return &Manager{current: make(map[string]*Node)}
}

func ValidateConfigs(configs []UpstreamConfig) error {
	seen := make(map[string]struct{}, len(configs))
	for _, cfg := range configs {
		if cfg.ID == "" {
			return fmt.Errorf("upstream id must not be empty")
		}
		if _, ok := seen[cfg.ID]; ok {
			return fmt.Errorf("duplicate upstream id %q", cfg.ID)
		}
		seen[cfg.ID] = struct{}{}
		if cfg.Concurrency <= 0 {
			return fmt.Errorf("upstream %q concurrency must be a positive integer", cfg.ID)
		}
		if err := validateAddress(cfg.Address); err != nil {
			return fmt.Errorf("upstream %q: %w", cfg.ID, err)
		}
	}
	return nil
}

func validateAddress(address string) error {
	u, err := url.Parse(address)
	if err != nil {
		return err
	}
	if u.Scheme != "http" || u.Host == "" || u.Opaque != "" || u.User != nil || u.Path != "" || u.RawPath != "" || u.RawQuery != "" || u.Fragment != "" || u.RawFragment != "" {
		return fmt.Errorf("address must be an http://host:port URL without path, query, fragment, or user info")
	}
	host, portText, err := net.SplitHostPort(u.Host)
	if err != nil || host == "" {
		return fmt.Errorf("address must contain host and port")
	}
	port, err := strconv.Atoi(portText)
	if err != nil || port < 1 || port > 65535 {
		return fmt.Errorf("port must be between 1 and 65535")
	}
	return nil
}

func (m *Manager) Replace(configs []UpstreamConfig) (Snapshot, error) {
	if configs == nil {
		return m.Snapshot(), fmt.Errorf("configuration must be a JSON array, even when empty")
	}
	if err := ValidateConfigs(configs); err != nil {
		return m.Snapshot(), err
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	next := make(map[string]*Node, len(configs))
	var newDraining []*Node
	for _, cfg := range configs {
		old := m.current[cfg.ID]
		if old != nil && old.address == cfg.Address {
			old.limit = cfg.Concurrency
			next[cfg.ID] = old
			continue
		}
		if old != nil {
			old.drainLocked()
			newDraining = append(newDraining, old)
		}
		m.nextGen++
		next[cfg.ID] = newNode(m.nextGen, cfg)
	}
	for _, old := range m.current {
		if _, kept := next[old.id]; !kept {
			old.drainLocked()
			newDraining = append(newDraining, old)
		}
	}
	m.draining = append(m.draining, newDraining...)
	m.pruneDrainingLocked()
	m.current = next
	m.version++
	return m.snapshotLocked(), nil
}

func (n *Node) drainLocked() {
	if n.draining {
		return
	}
	n.draining = true
	if n.inFlight == 0 {
		n.transport.CloseIdleConnections()
	}
}

func (m *Manager) pruneDrainingLocked() {
	kept := m.draining[:0]
	for _, n := range m.draining {
		if n.inFlight > 0 {
			kept = append(kept, n)
		}
	}
	m.draining = kept
}

func (m *Manager) Acquire() (*Node, Snapshot, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()

	var selected *Node
	for _, n := range m.current {
		if n.inFlight >= n.limit {
			continue
		}
		if selected == nil || n.inFlight < selected.inFlight || (n.inFlight == selected.inFlight && n.id < selected.id) {
			selected = n
		}
	}
	if selected == nil {
		return nil, m.snapshotLocked(), false
	}
	selected.inFlight++
	selected.wg.Add(1)
	return selected, m.snapshotLocked(), true
}

func (m *Manager) Release(n *Node) {
	m.mu.Lock()
	if n.inFlight <= 0 {
		m.mu.Unlock()
		panic("release of node with no in-flight requests")
	}
	n.inFlight--
	zeroAndDraining := n.draining && n.inFlight == 0
	m.pruneDrainingLocked()
	m.mu.Unlock()

	n.wg.Done()
	if zeroAndDraining {
		n.transport.CloseIdleConnections()
	}
}

func (m *Manager) Snapshot() Snapshot {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.snapshotLocked()
}

func (m *Manager) snapshotLocked() Snapshot {
	current := make([]NodeStatus, 0, len(m.current))
	for _, n := range m.current {
		current = append(current, n.status())
	}
	sort.Slice(current, func(i, j int) bool { return current[i].ID < current[j].ID })
	draining := make([]NodeStatus, 0, len(m.draining))
	for _, n := range m.draining {
		if n.inFlight > 0 {
			draining = append(draining, n.status())
		}
	}
	sort.Slice(draining, func(i, j int) bool {
		if draining[i].ID != draining[j].ID {
			return draining[i].ID < draining[j].ID
		}
		return draining[i].Generation < draining[j].Generation
	})
	return Snapshot{Version: m.version, Current: current, Draining: draining}
}

func (m *Manager) WaitDrained(timeout time.Duration) bool {
	m.mu.Lock()
	nodes := make([]*Node, 0, len(m.current)+len(m.draining))
	for _, n := range m.current {
		n.drainLocked()
		nodes = append(nodes, n)
	}
	nodes = append(nodes, m.draining...)
	m.mu.Unlock()

	done := make(chan struct{})
	go func() {
		for _, n := range nodes {
			n.wait()
		}
		close(done)
	}()
	if timeout <= 0 {
		<-done
		return true
	}
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-done:
		return true
	case <-timer.C:
		return false
	}
}
