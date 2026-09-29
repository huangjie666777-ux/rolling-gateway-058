package main

import (
	"context"
	"io"
	"net/http"
	"strings"
)

var hopByHopHeaders = []string{
	"Connection",
	"Proxy-Connection",
	"Keep-Alive",
	"Proxy-Authenticate",
	"Proxy-Authorization",
	"Te",
	"Trailer",
	"Transfer-Encoding",
	"Upgrade",
}

type Proxy struct {
	nodes *Manager
}

func NewProxy(nodes *Manager) http.Handler {
	return &Proxy{nodes: nodes}
}

func (p *Proxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	node, _, ok := p.nodes.Acquire()
	if !ok {
		http.Error(w, "no upstream capacity", http.StatusServiceUnavailable)
		return
	}
	released := false
	release := func() {
		if !released {
			released = true
			p.nodes.Release(node)
		}
	}
	defer release()

	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	targetURL := *r.URL
	targetURL.Scheme = "http"
	targetURL.Host = strings.TrimPrefix(node.address, "http://")
	outReq := r.Clone(ctx)
	outReq.URL = &targetURL
	outReq.Host = targetURL.Host
	outReq.RequestURI = ""
	removeHopHeaders(outReq.Header)

	response, err := node.transport.RoundTrip(outReq)
	if err != nil {
		if ctx.Err() == nil {
			http.Error(w, "upstream connection failed", http.StatusBadGateway)
		}
		return
	}
	defer response.Body.Close()

	removeHopHeaders(response.Header)
	copyHeaders(w.Header(), response.Header)
	w.WriteHeader(response.StatusCode)
	streamBody(w, response.Body)
}

func removeHopHeaders(header http.Header) {
	for _, token := range header.Values("Connection") {
		for _, part := range strings.Split(token, ",") {
			header.Del(strings.TrimSpace(part))
		}
	}
	for _, name := range hopByHopHeaders {
		header.Del(name)
	}
}

func copyHeaders(dst, src http.Header) {
	for name, values := range src {
		for _, value := range values {
			dst.Add(name, value)
		}
	}
}

func streamBody(w http.ResponseWriter, body io.Reader) {
	flusher, _ := w.(http.Flusher)
	buffer := make([]byte, 32*1024)
	for {
		n, err := body.Read(buffer)
		if n > 0 {
			if _, writeErr := w.Write(buffer[:n]); writeErr != nil {
				return
			}
			if flusher != nil {
				flusher.Flush()
			}
		}
		if err != nil {
			return
		}
	}
}
