package main

import (
	"errors"
	"net/http"
	"net/http/httputil"
	"net/url"
)

type GatewayHandler struct {
	pool *Pool
}

func NewGatewayHandler(pool *Pool) http.Handler {
	return &GatewayHandler{pool: pool}
}

func (h *GatewayHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	lease, err := h.pool.Acquire(r.Context())
	if err != nil {
		if errors.Is(err, ErrNoCapacity) {
			http.Error(w, "no upstream capacity", http.StatusServiceUnavailable)
			return
		}
		http.Error(w, err.Error(), http.StatusServiceUnavailable)
		return
	}
	defer lease.release()

	targetURL, err := url.Parse(lease.node.address)
	if err != nil {
		http.Error(w, "invalid upstream address", http.StatusBadGateway)
		return
	}

	proxy := &httputil.ReverseProxy{
		Transport:     lease.node.transport,
		FlushInterval: -1,
		ErrorHandler:  proxyErrorHandler,
		Director:      proxyDirector(targetURL),
	}
	proxy.ServeHTTP(w, r)
}

func proxyDirector(target *url.URL) func(*http.Request) {
	return func(req *http.Request) {
		req.URL.Scheme = target.Scheme
		req.URL.Host = target.Host
		req.Host = target.Host
		req.RequestURI = ""
		for _, header := range hopHeaders {
			req.Header.Del(header)
		}
	}
}

func proxyErrorHandler(w http.ResponseWriter, _ *http.Request, err error) {
	http.Error(w, "bad gateway: "+err.Error(), http.StatusBadGateway)
}

var hopHeaders = []string{
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
