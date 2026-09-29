package main

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestProxyForwardsAndStreamsUntilBodyEnd(t *testing.T) {
	started := make(chan struct{})
	releaseBody := make(chan struct{})
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.Copy(io.Discard, r.Body)
		w.Header().Set("X-Up-Host", r.Host)
		w.WriteHeader(http.StatusTeapot)
		_, _ = w.Write([]byte("before-"))
		if flusher, ok := w.(http.Flusher); ok {
			flusher.Flush()
		}
		close(started)
		select {
		case <-r.Context().Done():
			return
		case <-releaseBody:
		}
		_, _ = w.Write([]byte("after"))
	}))
	t.Cleanup(upstream.Close)

	m := NewManager()
	_, _ = m.Replace([]UpstreamConfig{{ID: "a", Address: upstream.URL, Concurrency: 1}})
	proxy := httptest.NewServer(NewProxy(m))
	t.Cleanup(proxy.Close)

	responseBodyComplete := make(chan struct{})
	go func() {
		defer close(responseBodyComplete)
		req, _ := http.NewRequest(http.MethodPut, proxy.URL+"/path?x=1", strings.NewReader("payload"))
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			return
		}
		defer resp.Body.Close()
		if resp.StatusCode != http.StatusTeapot || resp.Header.Get("X-Up-Host") != strings.TrimPrefix(upstream.URL, "http://") {
			t.Errorf("unexpected response: status=%d host=%q", resp.StatusCode, resp.Header.Get("X-Up-Host"))
		}
		data, _ := io.ReadAll(resp.Body)
		if string(data) != "before-after" {
			t.Errorf("unexpected body %q", data)
		}
	}()

	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("upstream did not start")
	}
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if m.Snapshot().Current[0].InFlight == 1 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if got := m.Snapshot().Current[0].InFlight; got != 1 {
		t.Fatalf("count released before body ended: %d", got)
	}

	close(releaseBody)
	select {
	case <-responseBodyComplete:
	case <-time.After(time.Second):
		t.Fatal("client did not finish")
	}
	deadline = time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if m.Snapshot().Current[0].InFlight == 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("in-flight count not released after body")
}

func TestProxyUnavailableAndBadGateway(t *testing.T) {
	m := NewManager()
	proxy := httptest.NewServer(NewProxy(m))
	t.Cleanup(proxy.Close)

	resp, err := http.Post(proxy.URL, "text/plain", strings.NewReader("x"))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("expected 503, got %d", resp.StatusCode)
	}

	_, _ = m.Replace([]UpstreamConfig{{ID: "bad", Address: "http://127.0.0.1:1", Concurrency: 1}})
	resp, err = http.Get(proxy.URL)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadGateway {
		t.Fatalf("expected 502, got %d", resp.StatusCode)
	}
}

func TestAdminAtomicReplace(t *testing.T) {
	m := NewManager()
	admin := httptest.NewServer(NewAdminHandler(m))
	t.Cleanup(admin.Close)

	put := func(body string) *http.Response {
		req, _ := http.NewRequest(http.MethodPut, admin.URL+"/admin/upstreams", bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		_, _ = io.Copy(io.Discard, resp.Body)
		return resp
	}

	query := func() Snapshot {
		getResp, err := http.Get(admin.URL + "/admin/upstreams")
		if err != nil {
			t.Fatal(err)
		}
		defer getResp.Body.Close()
		var snapshot Snapshot
		if err := json.NewDecoder(getResp.Body).Decode(&snapshot); err != nil {
			t.Fatal(err)
		}
		return snapshot
	}

	resp := put(`[{"id":"a","address":"http://127.0.0.1:9001","concurrency":2}]`)
	snapshot := query()
	if resp.StatusCode != http.StatusOK || snapshot.Version != 1 || len(snapshot.Current) != 1 {
		t.Fatalf("valid replace failed: status=%d snapshot=%+v", resp.StatusCode, snapshot)
	}
	resp = put(`[{"id":"b","address":"https://127.0.0.1:9002","concurrency":1}]`)
	snapshot = query()
	if resp.StatusCode != http.StatusBadRequest || snapshot.Version != 1 {
		t.Fatalf("invalid replace changed state: status=%d snapshot=%+v", resp.StatusCode, snapshot)
	}

	if snapshot.Version != 1 || snapshot.Current[0].ID != "a" {
		t.Fatalf("old config not preserved: %+v", snapshot)
	}
}

func TestClientDisconnectCancelsUpstream(t *testing.T) {
	upstreamStarted := make(chan struct{})
	upstreamDone := make(chan struct{})
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(upstreamStarted)
		<-r.Context().Done()
		close(upstreamDone)
	}))
	t.Cleanup(upstream.Close)

	m := NewManager()
	_, _ = m.Replace([]UpstreamConfig{{ID: "a", Address: upstream.URL, Concurrency: 1}})
	proxy := httptest.NewServer(NewProxy(m))
	t.Cleanup(proxy.Close)

	ctx, cancel := context.WithCancel(context.Background())
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, proxy.URL, nil)
	go func() {
		resp, err := http.DefaultClient.Do(req)
		if err == nil {
			resp.Body.Close()
		}
	}()
	<-upstreamStarted
	cancel()
	select {
	case <-upstreamDone:
	case <-time.After(time.Second):
		t.Fatal("upstream request was not canceled")
	}
}
