package main

import (
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"time"
)

func main() {
	name := env("UPSTREAM_NAME", "upstream")
	addr := env("UPSTREAM_ADDR", ":9001")

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte("ok")) })
	mux.HandleFunc("/echo", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_, _ = fmt.Fprintf(w, "%s %s?%s\n", name, r.URL.Path, r.URL.RawQuery)
		_, _ = io.Copy(w, r.Body)
	})
	mux.HandleFunc("/stream", func(w http.ResponseWriter, _ *http.Request) {
		flusher, _ := w.(http.Flusher)
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.WriteHeader(http.StatusOK)
		for i := 1; i <= 5; i++ {
			_, _ = fmt.Fprintf(w, "%s chunk %d\n", name, i)
			if flusher != nil {
				flusher.Flush()
			}
			time.Sleep(200 * time.Millisecond)
		}
	})
	mux.HandleFunc("/slow", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		flusher, _ := w.(http.Flusher)
		w.WriteHeader(http.StatusOK)
		for i := 1; ; i++ {
			select {
			case <-r.Context().Done():
				log.Printf("%s client canceled", name)
				return
			case <-time.After(time.Second):
				_, _ = fmt.Fprintf(w, "tick %d\n", i)
				if flusher != nil {
					flusher.Flush()
				}
			}
		}
	})

	log.Printf("%s listening on %s", name, addr)
	log.Fatal(http.ListenAndServe(addr, mux))
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
