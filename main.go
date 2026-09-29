package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	proxyAddr := flag.String("proxy-addr", envString("PROXY_ADDR", ":8080"), "proxy listen address")
	adminAddr := flag.String("admin-addr", envString("ADMIN_ADDR", ":8081"), "admin listen address")
	drainTimeout := flag.Duration("drain-timeout", envDuration("DRAIN_TIMEOUT", 30*time.Second), "maximum graceful drain time")
	flag.Parse()

	nodes := NewManager()
	proxyListenerCtx, stopProxyAccept := context.WithCancel(context.Background())
	proxyServer := &http.Server{
		Addr:    *proxyAddr,
		Handler: NewProxy(nodes),
		BaseContext: func(_ net.Listener) context.Context {
			return proxyListenerCtx
		},
	}
	adminServer := &http.Server{
		Addr:    *adminAddr,
		Handler: NewAdminHandler(nodes),
	}

	errorsCh := make(chan error, 2)
	go func() { errorsCh <- proxyServer.ListenAndServe() }()
	go func() { errorsCh <- adminServer.ListenAndServe() }()
	log.Printf("proxy listening on %s, admin listening on %s", *proxyAddr, *adminAddr)

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	select {
	case sig := <-signals:
		log.Printf("received %s, stopping proxy acceptance and draining", sig)
	case err := <-errorsCh:
		if !errors.Is(err, http.ErrServerClosed) {
			log.Fatal(err)
		}
	}

	proxyDone := make(chan struct{})
	go func() {
		_ = proxyServer.Shutdown(context.Background())
		close(proxyDone)
	}()
	timer := time.NewTimer(*drainTimeout)
	select {
	case <-proxyDone:
		timer.Stop()
		log.Print("all proxy requests drained")
	case <-timer.C:
		log.Printf("drain timeout %s expired; canceling remaining requests", *drainTimeout)
		stopProxyAccept()
		<-proxyDone
	}

	adminShutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := adminServer.Shutdown(adminShutdownCtx); err != nil {
		log.Printf("admin shutdown error: %v", err)
	}
}

func envString(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func envDuration(key string, fallback time.Duration) time.Duration {
	if value := os.Getenv(key); value != "" {
		if parsed, err := time.ParseDuration(value); err == nil {
			return parsed
		}
	}
	return fallback
}
