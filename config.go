package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

type UpstreamConfig struct {
	ID      string `json:"id"`
	Address string `json:"address"`
	Limit   int    `json:"limit"`
}

type ConfigSnapshot struct {
	Version   int              `json:"version"`
	Upstreams []UpstreamConfig `json:"upstreams"`
}

func validateUpstreams(input []UpstreamConfig) error {
	seen := make(map[string]struct{}, len(input))
	for i, upstream := range input {
		if strings.TrimSpace(upstream.ID) == "" {
			return fmt.Errorf("upstreams[%d].id must be non-empty", i)
		}
		if _, exists := seen[upstream.ID]; exists {
			return fmt.Errorf("upstream id %q is duplicated", upstream.ID)
		}
		seen[upstream.ID] = struct{}{}

		if upstream.Limit <= 0 {
			return fmt.Errorf("upstream %q limit must be a positive integer", upstream.ID)
		}

		parsed, err := url.Parse(upstream.Address)
		if err != nil {
			return fmt.Errorf("upstream %q address is invalid: %w", upstream.ID, err)
		}
		if parsed.Scheme != "http" {
			return fmt.Errorf("upstream %q address must use http", upstream.ID)
		}
		if parsed.User != nil {
			return fmt.Errorf("upstream %q address must only contain host and port", upstream.ID)
		}
		if parsed.Path != "" {
			return fmt.Errorf("upstream %q address must not contain a path", upstream.ID)
		}
		if parsed.RawPath != "" || parsed.RawQuery != "" || parsed.Fragment != "" || parsed.RawFragment != "" {
			return fmt.Errorf("upstream %q address must only contain host and port", upstream.ID)
		}
		host, port, err := net.SplitHostPort(parsed.Host)
		if err != nil || host == "" || port == "" {
			return fmt.Errorf("upstream %q address must contain a host and port", upstream.ID)
		}
		portNumber, err := strconv.Atoi(port)
		if err != nil || portNumber < 1 || portNumber > 65535 {
			return fmt.Errorf("upstream %q port must be between 1 and 65535", upstream.ID)
		}
		if parsed.Host != net.JoinHostPort(host, port) {
			return fmt.Errorf("upstream %q address must contain a host and port", upstream.ID)
		}
	}
	return nil
}

func decodeUpstreams(r *http.Request) ([]UpstreamConfig, error) {
	payload, err := io.ReadAll(io.LimitReader(r.Body, 1<<20+1))
	if err != nil {
		return nil, err
	}
	if len(payload) > 1<<20 {
		return nil, fmt.Errorf("request body exceeds 1 MiB")
	}
	trimmed := bytes.TrimSpace(payload)
	if len(trimmed) == 0 || trimmed[0] != '[' {
		return nil, fmt.Errorf("request body must be a JSON array")
	}
	var upstreams []UpstreamConfig
	var rawItems []json.RawMessage
	if err := json.Unmarshal(trimmed, &rawItems); err != nil {
		return nil, err
	}
	upstreams = make([]UpstreamConfig, 0, len(rawItems))
	for _, raw := range rawItems {
		decoder := json.NewDecoder(bytes.NewReader(raw))
		decoder.DisallowUnknownFields()
		var upstream UpstreamConfig
		if err := decoder.Decode(&upstream); err != nil {
			return nil, err
		}
		upstreams = append(upstreams, upstream)
	}
	return upstreams, nil
}
