package main

import (
	"encoding/json"
	"log"
	"net/http"

	"github.com/go-chi/chi/v5"
)

func NewAdminRouter(pool *Pool) http.Handler {
	r := chi.NewRouter()
	r.Get("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	r.Get("/admin/config", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, pool.Config())
	})
	r.Put("/admin/upstreams", func(w http.ResponseWriter, r *http.Request) {
		upstreams, err := decodeUpstreams(r)
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
			return
		}
		snapshot, err := pool.Publish(upstreams)
		if err != nil {
			log.Printf("reject upstream publish: %v", err)
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": err.Error(), "version": snapshot.Version})
			return
		}
		writeJSON(w, http.StatusOK, snapshot)
	})
	r.Get("/admin/nodes", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, pool.Status())
	})
	return r
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
