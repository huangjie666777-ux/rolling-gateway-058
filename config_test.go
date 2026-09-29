package main

import (
	"testing"
)

func TestValidateConfigs(t *testing.T) {
	valid := []UpstreamConfig{
		{ID: "a", Address: "http://127.0.0.1:9001", Concurrency: 1},
		{ID: "b", Address: "http://[::1]:9002", Concurrency: 2},
	}
	if err := ValidateConfigs(valid); err != nil {
		t.Fatalf("valid config rejected: %v", err)
	}

	invalid := [][]UpstreamConfig{
		{{ID: "a", Address: "https://127.0.0.1:9001", Concurrency: 1}},
		{{ID: "a", Address: "http://127.0.0.1:9001/path", Concurrency: 1}},
		{{ID: "a", Address: "http://127.0.0.1", Concurrency: 1}},
		{{ID: "a", Address: "http://127.0.0.1:9001", Concurrency: 0}},
		{{ID: "a", Address: "http://127.0.0.1:9001", Concurrency: 1}, {ID: "a", Address: "http://127.0.0.1:9002", Concurrency: 1}},
	}
	for i, cfg := range invalid {
		if err := ValidateConfigs(cfg); err == nil {
			t.Fatalf("invalid case %d accepted", i)
		}
	}
}

func TestSelectionLimitAndRollingGeneration(t *testing.T) {
	m := NewManager()
	if _, err := m.Replace([]UpstreamConfig{
		{ID: "a", Address: "http://127.0.0.1:9001", Concurrency: 2},
		{ID: "b", Address: "http://127.0.0.1:9002", Concurrency: 2},
	}); err != nil {
		t.Fatal(err)
	}

	first, _, ok := m.Acquire()
	if !ok || first.id != "a" {
		t.Fatalf("expected lowest id a, got %#v, ok=%v", first, ok)
	}

	if _, err := m.Replace([]UpstreamConfig{
		{ID: "a", Address: "http://127.0.0.1:9001", Concurrency: 1},
	}); err != nil {
		t.Fatal(err)
	}
	if _, _, ok := m.Acquire(); ok {
		t.Fatal("lowered limit accepted an additional request")
	}

	if _, err := m.Replace([]UpstreamConfig{
		{ID: "a", Address: "http://127.0.0.1:9901", Concurrency: 2},
		{ID: "b", Address: "http://127.0.0.1:9002", Concurrency: 2},
	}); err != nil {
		t.Fatal(err)
	}
	snapshot := m.Snapshot()
	if got := len(snapshot.Draining); got != 1 || snapshot.Draining[0].Address != "http://127.0.0.1:9001" || snapshot.Draining[0].InFlight != 1 {
		t.Fatalf("unexpected draining snapshot: %+v", snapshot.Draining)
	}
	m.Release(first)
	snapshot = m.Snapshot()
	if len(snapshot.Draining) != 0 {
		t.Fatalf("drained old generation not removed: %+v", snapshot.Draining)
	}

	newA, _, ok := m.Acquire()
	if !ok || newA.address != "http://127.0.0.1:9901" || newA.inFlight != 1 {
		t.Fatalf("new generation not selected: %#v ok=%v", newA, ok)
	}
	m.Release(newA)
}

func TestSameNodeUpdateRetainsInFlight(t *testing.T) {
	m := NewManager()
	_, _ = m.Replace([]UpstreamConfig{{ID: "a", Address: "http://127.0.0.1:9001", Concurrency: 1}})
	node, _, _ := m.Acquire()
	snapshot, err := m.Replace([]UpstreamConfig{{ID: "a", Address: "http://127.0.0.1:9001", Concurrency: 3}})
	if err != nil {
		t.Fatal(err)
	}
	if len(snapshot.Current) != 1 || snapshot.Current[0].InFlight != 1 || snapshot.Current[0].Concurrency != 3 {
		t.Fatalf("in-flight count not retained: %+v", snapshot.Current)
	}
	if len(snapshot.Draining) != 0 {
		t.Fatalf("same-address update should not drain: %+v", snapshot.Draining)
	}
	m.Release(node)
}
