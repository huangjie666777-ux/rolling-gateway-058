package main

import (
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"time"
)

func main() {
	addr := flag.String("addr", ":9001", "listen address")
	id := flag.String("id", "local", "upstream identifier returned by responses")
	flag.Parse()

	mux := http.NewServeMux()
	mux.HandleFunc("/echo", func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"id":%q,"method":%q,"path":%q,"query":%q,"host":%q,"body":%q}`, *id, r.Method, r.URL.Path, r.URL.RawQuery, r.Host, string(body))
	})
	mux.HandleFunc("/slow", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		flusher := w.(http.Flusher)
		for i := 0; i < 10; i++ {
			select {
			case <-time.After(200 * time.Millisecond):
				fmt.Fprintf(w, "chunk-%d from %s\n", i, *id)
				flusher.Flush()
			case <-r.Context().Done():
				return
			}
		}
	})
	mux.HandleFunc("/stream", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		flusher := w.(http.Flusher)
		for i := 0; i < 5; i++ {
			fmt.Fprintf(w, "stream-%d from %s\n", i, *id)
			flusher.Flush()
			time.Sleep(100 * time.Millisecond)
		}
	})

	server := &http.Server{Addr: *addr, Handler: mux}
	log.Printf("example upstream %s listening on %s", *id, *addr)
	log.Fatal(server.ListenAndServe())
}
