package main

import (
	"context"
	"testing"
)

func TestPublishValidatesAtomically(t *testing.T) {
	pool := NewPool()
	_, err := pool.Publish([]UpstreamConfig{
		{ID: "a", Address: "http://127.0.0.1:9001", Limit: 2},
	})
	if err != nil {
		t.Fatal(err)
	}

	_, err = pool.Publish([]UpstreamConfig{
		{ID: "a", Address: "http://127.0.0.1:9001", Limit: 1},
		{ID: "bad", Address: "127.0.0.1:9002", Limit: 1},
	})
	if err == nil {
		t.Fatal("expected invalid publish to fail")
	}

	snapshot := pool.Config()
	if snapshot.Version != 1 || len(snapshot.Upstreams) != 1 || snapshot.Upstreams[0].Limit != 2 {
		t.Fatalf("old config changed after invalid publish: %+v", snapshot)
	}
}

func TestAcquireLeastLoadedAndCapacity(t *testing.T) {
	pool := NewPool()
	_, err := pool.Publish([]UpstreamConfig{
		{ID: "a", Address: "http://127.0.0.1:9001", Limit: 1},
		{ID: "b", Address: "http://127.0.0.1:9002", Limit: 1},
	})
	if err != nil {
		t.Fatal(err)
	}

	first, err := pool.Acquire(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if first.node.id != "a" {
		t.Fatalf("first selection = %q, want a", first.node.id)
	}

	second, err := pool.Acquire(context.Background())
	if err != nil || second.node.id != "b" {
		t.Fatalf("second selection = %v, %v; want b", second, err)
	}

	if _, err := pool.Acquire(context.Background()); err != ErrNoCapacity {
		t.Fatalf("third acquire error = %v, want ErrNoCapacity", err)
	}

	first.release()
	third, err := pool.Acquire(context.Background())
	if err != nil || third.node.id != "a" {
		t.Fatalf("third selection = %v, %v; want a", third, err)
	}
	second.release()
	third.release()
}

func TestAddressChangeDrainsOldGeneration(t *testing.T) {
	pool := NewPool()
	_, err := pool.Publish([]UpstreamConfig{
		{ID: "a", Address: "http://127.0.0.1:9001", Limit: 1},
	})
	if err != nil {
		t.Fatal(err)
	}

	oldLease, err := pool.Acquire(context.Background())
	if err != nil {
		t.Fatal(err)
	}

	if _, err := pool.Publish([]UpstreamConfig{
		{ID: "a", Address: "http://127.0.0.1:9002", Limit: 1},
	}); err != nil {
		t.Fatal(err)
	}

	newLease, err := pool.Acquire(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if newLease.node.generation != 2 || newLease.node.address != "http://127.0.0.1:9002" {
		t.Fatalf("new lease = generation %d address %q", newLease.node.generation, newLease.node.address)
	}

	statuses := pool.Status()
	if len(statuses) != 2 {
		t.Fatalf("statuses = %+v, want old and current node", statuses)
	}

	oldLease.release()
	statuses = pool.Status()
	if len(statuses) != 1 || statuses[0].State != "active" || statuses[0].Inflight != 1 {
		t.Fatalf("statuses after old release = %+v", statuses)
	}
	newLease.release()
}

func TestSameAddressUpdateKeepsInflight(t *testing.T) {
	pool := NewPool()
	_, _ = pool.Publish([]UpstreamConfig{{ID: "a", Address: "http://127.0.0.1:9001", Limit: 1}})
	lease, err := pool.Acquire(context.Background())
	if err != nil {
		t.Fatal(err)
	}

	_, err = pool.Publish([]UpstreamConfig{{ID: "a", Address: "http://127.0.0.1:9001", Limit: 3}})
	if err != nil {
		t.Fatal(err)
	}
	statuses := pool.Status()
	if len(statuses) != 1 || statuses[0].Inflight != 1 || statuses[0].Limit != 3 || statuses[0].Generation != 1 {
		t.Fatalf("status = %+v", statuses)
	}
	lease.release()
}
