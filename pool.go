package main

import (
	"context"
	"errors"
	"net/http"
	"sort"
	"strconv"
	"sync"
)

var ErrNoCapacity = errors.New("no upstream capacity")

type Node struct {
	id         string
	address    string
	limit      int
	inflight   int
	generation int
	draining   bool
	transport  *http.Transport
}

type Lease struct {
	node *Node
	pool *Pool
}

type NodeStatus struct {
	ID         string `json:"id"`
	Address    string `json:"address"`
	Limit      int    `json:"limit"`
	Inflight   int    `json:"inflight"`
	Generation int    `json:"generation"`
	State      string `json:"state"`
}

type Pool struct {
	mu       sync.Mutex
	current  map[string]*Node
	draining map[string]*Node
	version  int
	closed   bool
}

func NewPool() *Pool {
	return &Pool{
		current:  make(map[string]*Node),
		draining: make(map[string]*Node),
	}
}

func (p *Pool) Publish(input []UpstreamConfig) (ConfigSnapshot, error) {
	if err := validateUpstreams(input); err != nil {
		return p.Config(), err
	}

	p.mu.Lock()
	if p.closed {
		snapshot := ConfigSnapshot{Version: p.version, Upstreams: p.configLocked()}
		p.mu.Unlock()
		return snapshot, errors.New("gateway is shutting down")
	}

	nextVersion := p.version + 1
	next := make(map[string]*Node, len(input))
	copied := make([]UpstreamConfig, 0, len(input))
	retired := make([]*Node, 0)

	for _, cfg := range input {
		copied = append(copied, cfg)
		if old, exists := p.current[cfg.ID]; exists && old.address == cfg.Address {
			old.limit = cfg.Limit
			next[cfg.ID] = old
			continue
		}

		if old, exists := p.current[cfg.ID]; exists {
			old.draining = true
			if old.inflight > 0 {
				p.draining[nodeKey(old.generation, old.id)] = old
			} else {
				retired = append(retired, old)
			}
		}

		next[cfg.ID] = newNode(cfg, nextVersion)
	}

	for id, node := range p.current {
		if _, kept := next[id]; !kept {
			node.draining = true
			if node.inflight > 0 {
				p.draining[nodeKey(node.generation, node.id)] = node
			} else {
				retired = append(retired, node)
			}
		}
	}

	p.current = next
	p.version = nextVersion
	p.mu.Unlock()
	for _, node := range retired {
		node.transport.CloseIdleConnections()
	}
	return ConfigSnapshot{Version: p.version, Upstreams: copied}, nil
}

func newNode(cfg UpstreamConfig, generation int) *Node {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	return &Node{
		id:         cfg.ID,
		address:    cfg.Address,
		limit:      cfg.Limit,
		generation: generation,
		transport:  transport,
	}
}

func (p *Pool) Acquire(ctx context.Context) (*Lease, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	default:
	}

	p.mu.Lock()
	defer p.mu.Unlock()

	if p.closed {
		return nil, ErrNoCapacity
	}

	candidates := make([]*Node, 0, len(p.current))
	for _, node := range p.current {
		if !node.draining && node.inflight < node.limit {
			candidates = append(candidates, node)
		}
	}
	if len(candidates) == 0 {
		return nil, ErrNoCapacity
	}

	sort.Slice(candidates, func(i, j int) bool {
		if candidates[i].inflight != candidates[j].inflight {
			return candidates[i].inflight < candidates[j].inflight
		}
		return candidates[i].id < candidates[j].id
	})
	selected := candidates[0]
	selected.inflight++
	return &Lease{node: selected, pool: p}, nil
}

func (l *Lease) release() {
	if l == nil || l.node == nil || l.pool == nil {
		return
	}
	p := l.pool
	node := l.node
	l.node = nil

	p.mu.Lock()
	node.inflight--
	shouldClose := false
	if node.draining && node.inflight == 0 {
		delete(p.draining, nodeKey(node.generation, node.id))
		shouldClose = true
	}
	p.mu.Unlock()

	if shouldClose {
		node.transport.CloseIdleConnections()
	}
}

func (p *Pool) Shutdown() {
	p.mu.Lock()
	p.closed = true
	p.mu.Unlock()
}

func (p *Pool) Config() ConfigSnapshot {
	p.mu.Lock()
	defer p.mu.Unlock()
	return ConfigSnapshot{Version: p.version, Upstreams: p.configLocked()}
}

func (p *Pool) configLocked() []UpstreamConfig {
	result := make([]UpstreamConfig, 0, len(p.current))
	for _, node := range p.current {
		result = append(result, UpstreamConfig{ID: node.id, Address: node.address, Limit: node.limit})
	}
	sort.Slice(result, func(i, j int) bool { return result[i].ID < result[j].ID })
	return result
}

func (p *Pool) Status() []NodeStatus {
	p.mu.Lock()
	defer p.mu.Unlock()

	statuses := make([]NodeStatus, 0, len(p.current)+len(p.draining))
	for _, node := range p.current {
		statuses = append(statuses, node.status("active"))
	}
	for _, node := range p.draining {
		statuses = append(statuses, node.status("draining"))
	}
	sort.Slice(statuses, func(i, j int) bool {
		if statuses[i].Generation != statuses[j].Generation {
			return statuses[i].Generation < statuses[j].Generation
		}
		return statuses[i].ID < statuses[j].ID
	})
	return statuses
}

func (n *Node) status(state string) NodeStatus {
	return NodeStatus{
		ID:         n.id,
		Address:    n.address,
		Limit:      n.limit,
		Inflight:   n.inflight,
		Generation: n.generation,
		State:      state,
	}
}

func nodeKey(generation int, id string) string {
	return strconv.Itoa(generation) + ":" + id
}
