package main

import (
	"encoding/json"
	"io"
	"net/http"

	"github.com/go-chi/chi/v5"
)

func NewAdminHandler(nodes *Manager) http.Handler {
	r := chi.NewRouter()
	r.Get("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	r.Get("/admin/upstreams", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, nodes.Snapshot())
	})
	r.Put("/admin/upstreams", func(w http.ResponseWriter, r *http.Request) {
		var configs []UpstreamConfig
		decoder := json.NewDecoder(r.Body)
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&configs); err != nil {
			http.Error(w, "invalid JSON: "+err.Error(), http.StatusBadRequest)
			return
		}
		if decoder.More() {
			http.Error(w, "invalid JSON: multiple values", http.StatusBadRequest)
			return
		}
		var extra json.RawMessage
		if err := decoder.Decode(&extra); err != io.EOF {
			http.Error(w, "invalid JSON: trailing value", http.StatusBadRequest)
			return
		}
		snapshot, err := nodes.Replace(configs)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		writeJSON(w, http.StatusOK, snapshot)
	})
	return r
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
