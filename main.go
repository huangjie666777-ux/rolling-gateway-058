package main

import (
	"log"
	"os"
	"strconv"
	"strings"
	"time"
)

func main() {
	config := Config{
		ProxyAddr:    env("PROXY_ADDR", ":8080"),
		AdminAddr:    env("ADMIN_ADDR", ":8081"),
		DrainTimeout: durationEnv("DRAIN_TIMEOUT", 30*time.Second),
		Initial:      initialUpstreams(),
	}
	if err := NewApp(config).Run(); err != nil {
		log.Fatal(err)
	}
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func durationEnv(key string, fallback time.Duration) time.Duration {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		log.Fatalf("invalid %s: %v", key, err)
	}
	return parsed
}

func initialUpstreams() []UpstreamConfig {
	raw := os.Getenv("UPSTREAMS")
	if raw == "" {
		return nil
	}
	parts := strings.Split(raw, ",")
	result := make([]UpstreamConfig, 0, len(parts))
	for i, part := range parts {
		colon := strings.LastIndex(part, ":")
		if colon <= 0 {
			log.Fatalf("UPSTREAMS item %d must be address:limit", i)
		}
		limit, err := strconv.Atoi(part[colon+1:])
		if err != nil || limit <= 0 {
			log.Fatalf("UPSTREAMS item %d limit must be positive", i)
		}
		result = append(result, UpstreamConfig{
			ID:      "upstream-" + strconv.Itoa(i+1),
			Address: part[:colon],
			Limit:   limit,
		})
	}
	return result
}
