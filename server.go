package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/go-chi/chi/v5"
)

type Config struct {
	ProxyAddr    string
	AdminAddr    string
	DrainTimeout time.Duration
	Initial      []UpstreamConfig
}

type App struct {
	config      Config
	pool        *Pool
	proxyServer *http.Server
	adminServer *http.Server
}

func NewApp(config Config) *App {
	pool := NewPool()
	if _, err := pool.Publish(config.Initial); err != nil {
		log.Fatalf("invalid initial upstreams: %v", err)
	}

	proxyRouter := chi.NewRouter()
	proxyRouter.Handle("/*", NewGatewayHandler(pool))

	return &App{
		config: config,
		pool:   pool,
		proxyServer: &http.Server{
			Addr:    config.ProxyAddr,
			Handler: proxyRouter,
		},
		adminServer: &http.Server{
			Addr:    config.AdminAddr,
			Handler: NewAdminRouter(pool),
		},
	}
}

func (a *App) Run() error {
	shutdownCtx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	proxyErr := make(chan error, 1)
	adminErr := make(chan error, 1)
	go func() { proxyErr <- a.serve(a.proxyServer, "proxy") }()
	go func() { adminErr <- a.serve(a.adminServer, "admin") }()

	select {
	case err := <-proxyErr:
		return err
	case err := <-adminErr:
		return err
	case <-shutdownCtx.Done():
		return a.Shutdown()
	}
}

func (a *App) serve(server *http.Server, name string) error {
	log.Printf("%s listening on %s", name, server.Addr)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

func (a *App) Shutdown() error {
	log.Println("shutting down and draining proxy requests")
	a.pool.Shutdown()

	proxyDone := make(chan error, 1)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), a.config.DrainTimeout)
		defer cancel()
		proxyDone <- a.proxyServer.Shutdown(ctx)
	}()

	timer := time.NewTimer(a.config.DrainTimeout)
	defer timer.Stop()
	select {
	case err := <-proxyDone:
		return a.shutdownAdmin(err)
	case <-timer.C:
		log.Println("drain timeout; canceling remaining requests")
		err := a.proxyServer.Close()
		_ = a.adminServer.Close()
		if drainErr := <-proxyDone; drainErr != nil {
			return drainErr
		}
		return err
	}
}

func (a *App) shutdownAdmin(proxyErr error) error {
	adminCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := a.adminServer.Shutdown(adminCtx); err != nil {
		_ = a.adminServer.Close()
		if proxyErr == nil {
			return err
		}
	}
	return proxyErr
}
