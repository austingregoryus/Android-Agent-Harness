// Command gateway is the Android Agent Harness Agent Gateway: it attaches a model provider
// to a paired Android embodiment runtime.
//
// Subcommands:
//
//	gateway run    --config config.yaml "task text"   run one agent task
//	gateway mcp    --config config.yaml               MCP stdio server for Claude Code
//	gateway pair   --config config.yaml --code XXXX-XXXX --label homelab
//	gateway health --config config.yaml               phone and provider health
//
// Bearer tokens and API keys are never printed by any subcommand.
package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/agent"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/config"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/device"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/mcpserver"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/provider"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/store"
)

const usage = `Android Agent Harness gateway

Usage:
  gateway run    --config <file> "task text"
  gateway mcp    --config <file>
  gateway pair   --config <file> --code XXXX-XXXX --label <label> [--scopes a,b] [--max-action-class CLASS]
  gateway health --config <file>

Flags are per subcommand; run "gateway <subcommand> -h" for detail.
`

func main() {
	if len(os.Args) < 2 {
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	var err error
	switch os.Args[1] {
	case "run":
		err = cmdRun(ctx, os.Args[2:])
	case "mcp":
		err = cmdMCP(ctx, os.Args[2:])
	case "pair":
		err = cmdPair(ctx, os.Args[2:])
	case "health":
		err = cmdHealth(ctx, os.Args[2:])
	case "-h", "--help", "help":
		fmt.Fprint(os.Stdout, usage)
		return
	default:
		fmt.Fprintf(os.Stderr, "unknown subcommand %q\n\n%s", os.Args[1], usage)
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "gateway: %v\n", err)
		os.Exit(1)
	}
}

// loadConfig reads the config file and resolves the device bearer token.
func loadConfig(path string) (*config.Config, string, error) {
	if path == "" {
		return nil, "", errors.New("--config is required")
	}
	cfg, err := config.Load(path)
	if err != nil {
		return nil, "", err
	}
	token, err := cfg.DeviceToken()
	if err != nil {
		return nil, "", err
	}
	return cfg, token, nil
}

// deviceHTTPClient returns the *http.Client shared by the MCP HTTP client and
// the /ws/events WebSocket dialer for one device connection.
//
// For a plain http:// base_url it returns nil, so device.NewMCPClient and
// device.NewEventClient fall back to their own defaults. For an https://
// base_url it resolves the pinned certificate fingerprint (config.Validate
// already guarantees one is configured) and returns one client whose
// transport pins it, wired into both the HTTP client and the WebSocket
// dialer so the phone's self-signed TLS-only connector is trusted exactly
// once, in one place.
func deviceHTTPClient(cfg *config.Config) (*http.Client, error) {
	if !strings.HasPrefix(strings.ToLower(strings.TrimSpace(cfg.Device.BaseURL)), "https://") {
		return nil, nil
	}
	fp, err := cfg.DeviceTLSFingerprint()
	if err != nil {
		return nil, err
	}
	client, err := device.NewPinnedHTTPClient(fp, 60*time.Second)
	if err != nil {
		return nil, fmt.Errorf("device TLS: %w", err)
	}
	return client, nil
}

// --- run --------------------------------------------------------------------

func cmdRun(ctx context.Context, argv []string) error {
	fs := flag.NewFlagSet("run", flag.ContinueOnError)
	cfgPath := fs.String("config", "", "path to config.yaml")
	timeout := fs.Duration("timeout", 5*time.Minute, "overall task timeout")
	if err := fs.Parse(argv); err != nil {
		return err
	}
	task := strings.TrimSpace(strings.Join(fs.Args(), " "))
	if task == "" {
		return errors.New("run: a task text argument is required")
	}
	cfg, token, err := loadConfig(*cfgPath)
	if err != nil {
		return err
	}

	st, err := store.Open(cfg.SQLitePath())
	if err != nil {
		return err
	}
	defer st.Close()

	httpClient, err := deviceHTTPClient(cfg)
	if err != nil {
		return err
	}
	dev := device.NewMCPClient(device.MCPOptions{BaseURL: cfg.Device.BaseURL, Token: token, HTTPClient: httpClient})
	defer func() {
		cctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		_ = dev.Close(cctx)
		cancel()
	}()

	providers, err := provider.ChainFromConfig(cfg)
	if err != nil {
		return err
	}

	ag, err := agent.New(agent.Options{
		Device:    dev,
		Providers: providers,
		Config:    cfg.Agent,
		Store:     st,
	})
	if err != nil {
		return err
	}

	runCtx, cancel := context.WithTimeout(ctx, *timeout)
	defer cancel()

	res, runErr := ag.Run(runCtx, task)
	printTrace(res)
	if runErr != nil {
		return runErr
	}
	fmt.Println(res.Answer)
	return nil
}

// printTrace writes a compact tool-call trace to stderr. It never prints
// credentials; tool arguments come from the model, not from configuration.
func printTrace(res agent.Result) {
	w := bufio.NewWriter(os.Stderr)
	defer w.Flush()
	fmt.Fprintf(w, "task %s  correlation %s  turns %d\n", res.TaskID, res.CorrelationID, res.Turns)
	for i, t := range res.Trace {
		status := "ok"
		if !t.OK {
			status = "ERR"
		}
		fmt.Fprintf(w, "  [%d] turn %d  %-5s %-24s %-3s  %s (%s)\n",
			i+1, t.Turn, t.Provider, t.Name, status, t.Summary, t.Duration.Round(time.Millisecond))
	}
	for _, s := range res.Switches {
		fmt.Fprintf(w, "  failover at turn %d: %s -> %s (%s)\n", s.Turn, s.From, s.To, s.Reason)
	}
}

// --- mcp --------------------------------------------------------------------

func cmdMCP(ctx context.Context, argv []string) error {
	fs := flag.NewFlagSet("mcp", flag.ContinueOnError)
	cfgPath := fs.String("config", "", "path to config.yaml")
	noEvents := fs.Bool("no-events", false, "disable the /ws/events channel (gateway.wait_for_event will error)")
	if err := fs.Parse(argv); err != nil {
		return err
	}
	cfg, token, err := loadConfig(*cfgPath)
	if err != nil {
		return err
	}

	st, err := store.Open(cfg.SQLitePath())
	if err != nil {
		return err
	}
	defer st.Close()

	httpClient, err := deviceHTTPClient(cfg)
	if err != nil {
		return err
	}
	dev := device.NewMCPClient(device.MCPOptions{BaseURL: cfg.Device.BaseURL, Token: token, HTTPClient: httpClient})
	// DELETE /mcp on the way out, so the runtime reclaims the session instead of
	// waiting for it to expire. The context is fresh: ctx is already cancelled
	// when the server exits on SIGTERM.
	defer func() {
		cctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		_ = dev.Close(cctx)
		cancel()
	}()

	var events mcpserver.Events
	if !*noEvents {
		ec := device.NewEventClient(device.EventOptions{
			BaseURL: cfg.Device.BaseURL, Token: token, Store: st, Recorder: st, HTTPClient: httpClient,
		})
		go func() {
			// The event channel is best-effort: a phone that is not reachable
			// must not stop the MCP server from serving tool proxies.
			_ = ec.Run(ctx)
		}()
		defer ec.Close()
		events = ec
	}

	// Providers are constructed best-effort so gateway.health can report on
	// them; missing API keys must not prevent the server from starting.
	var providers []provider.Provider
	for _, pc := range cfg.FailoverChain() {
		p, perr := provider.FromConfig(pc)
		if perr != nil {
			fmt.Fprintf(os.Stderr, "gateway mcp: provider %s unavailable: %v\n", pc.Name, perr)
			continue
		}
		providers = append(providers, p)
	}

	// converse.py defaults to the Pixel's secrets; name this gateway's phone explicitly, or a call
	// asked of another phone's gateway would be placed from the Pixel.
	var converseEnv []string
	converseDevice := []string{"--base", cfg.Device.BaseURL}
	if p := cfg.TokenFilePath(); p != "" {
		converseDevice = append(converseDevice, "--token-file", p)
	} else if tok, terr := cfg.DeviceToken(); terr == nil {
		// No token_file to point --token-file at (token comes from token_env): converse.py would otherwise
		// fall back to its own default secrets/device-token, i.e. the Pixel's token. Hand it this gateway's
		// token the same way converse.py's --token flag already does, via env, stripped and reset per call
		// in converseEnv so it can never leak to or from another invocation.
		converseEnv = append(converseEnv, "SYMBIOSIS_TOKEN="+tok)
	}
	if strings.HasPrefix(strings.ToLower(cfg.Device.BaseURL), "https://") {
		if fp, ferr := cfg.DeviceTLSFingerprint(); ferr == nil {
			converseDevice = append(converseDevice, "--pin", fp)
		}
	}

	srv, err := mcpserver.New(mcpserver.Options{
		Device:            dev,
		Events:            events,
		Providers:         providers,
		DeviceBaseURL:     cfg.Device.BaseURL,
		Allowlist:         cfg.Agent.ToolAllowlist,
		CorrelationPrefix: cfg.Agent.CorrelationPrefix,
		ConverseScript:    resolveConverseScript(*cfgPath, cfg.Agent.ConverseScript),
		Python:            cfg.Agent.Python,
		ConverseDevice:    converseDevice,
		ConverseEnv:       converseEnv,
	})
	if err != nil {
		return err
	}
	out := bufio.NewWriter(os.Stdout)
	defer out.Flush()
	return srv.Serve(ctx, os.Stdin, out)
}

// --- pair -------------------------------------------------------------------

func cmdPair(ctx context.Context, argv []string) error {
	fs := flag.NewFlagSet("pair", flag.ContinueOnError)
	cfgPath := fs.String("config", "", "path to config.yaml")
	code := fs.String("code", "", "one-time pairing code from the phone UI, e.g. K7Q2-9F4A")
	label := fs.String("label", "homelab", "human label for this peer")
	scopes := fs.String("scopes", "", "comma-separated capability globs (default: the gateway's standard set)")
	maxClass := fs.String("max-action-class", device.DefaultMaxActionClass, "requested action-class ceiling")
	if err := fs.Parse(argv); err != nil {
		return err
	}
	if *cfgPath == "" {
		return errors.New("--config is required")
	}
	if strings.TrimSpace(*code) == "" {
		return errors.New("--code is required")
	}
	cfg, err := config.Load(*cfgPath)
	if err != nil {
		return err
	}
	if cfg.TokenFilePath() == "" {
		return errors.New("pair: device.token_file must be set so the token can be persisted")
	}

	req := device.PairRequest{
		Code:           strings.TrimSpace(*code),
		Label:          *label,
		MaxActionClass: *maxClass,
	}
	if *scopes != "" {
		for _, s := range strings.Split(*scopes, ",") {
			if s = strings.TrimSpace(s); s != "" {
				req.Scopes = append(req.Scopes, s)
			}
		}
	}

	pctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	// Pair over the pinned client when the config names the phone's fingerprint; a nil client used default
	// verification and could never pair over the self-signed TLS connector (found by the first remote install).
	httpClient, err := deviceHTTPClient(cfg)
	if err != nil {
		return err
	}
	res, err := device.Pair(pctx, cfg.Device.BaseURL, req, httpClient)
	if err != nil {
		return err
	}
	if err := cfg.WriteDeviceToken(res.Token); err != nil {
		return err
	}
	// The token is deliberately not printed.
	fmt.Printf("paired peer_id=%s\n", res.PeerID)
	fmt.Printf("scopes=%s max_action_class=%s\n", strings.Join(res.Scopes, ","), res.MaxActionClass)
	fmt.Printf("token written to %s (not displayed)\n", cfg.TokenFilePath())

	// The fingerprint is not secret (it identifies a certificate, not a
	// credential), so unlike the token it is fine to persist opportunistically
	// and print in full.
	if res.TLSFingerprintSHA256 != "" {
		if err := cfg.WriteDeviceTLSFingerprint(res.TLSFingerprintSHA256); err != nil {
			return err
		}
		fmt.Printf("phone offers TLS: fingerprint %s written to %s — set device.tls_fingerprint_file (or tls_fingerprint_sha256) and device.base_url=%s to pin it\n",
			res.TLSFingerprintSHA256, cfg.TLSFingerprintFilePath(), firstNonEmpty(res.TLSMCPURL, "https://<phone-ip>:8766"))
	}
	return nil
}

// firstNonEmpty returns a, or b when a is empty.
func firstNonEmpty(a, b string) string {
	if a != "" {
		return a
	}
	return b
}

// --- health -----------------------------------------------------------------

func cmdHealth(ctx context.Context, argv []string) error {
	fs := flag.NewFlagSet("health", flag.ContinueOnError)
	cfgPath := fs.String("config", "", "path to config.yaml")
	if err := fs.Parse(argv); err != nil {
		return err
	}
	if *cfgPath == "" {
		return errors.New("--config is required")
	}
	cfg, err := config.Load(*cfgPath)
	if err != nil {
		return err
	}

	failed := false

	httpClient, cerr := deviceHTTPClient(cfg)
	if cerr != nil {
		failed = true
		fmt.Printf("phone   %-28s FAIL  %v\n", cfg.Device.BaseURL, cerr)
	} else {
		dev := device.NewMCPClient(device.MCPOptions{BaseURL: cfg.Device.BaseURL, Token: "", HTTPClient: httpClient})
		hctx, cancel := context.WithTimeout(ctx, 10*time.Second)
		h, herr := dev.Health(hctx)
		cancel()
		if herr != nil {
			failed = true
			fmt.Printf("phone   %-28s FAIL  %v\n", cfg.Device.BaseURL, herr)
		} else {
			fmt.Printf("phone   %-28s ok    device_id=%s version=%s\n", cfg.Device.BaseURL, h.DeviceID, h.Version)
		}
		if httpClient != nil {
			if fp, ferr := cfg.DeviceTLSFingerprint(); ferr == nil {
				fmt.Printf("tls: pinned %s\n", device.FingerprintPreview(fp))
			}
		}
	}

	for _, pc := range cfg.FailoverChain() {
		p, perr := provider.FromConfig(pc)
		if perr != nil {
			failed = true
			fmt.Printf("model   %-28s FAIL  %v\n", pc.Name, perr)
			continue
		}
		pctx, pcancel := context.WithTimeout(ctx, 20*time.Second)
		err := p.Health(pctx)
		pcancel()
		if err != nil {
			failed = true
			fmt.Printf("model   %-28s FAIL  %v\n", pc.Name, err)
			continue
		}
		fmt.Printf("model   %-28s ok    kind=%s model=%s\n", pc.Name, pc.Kind, modelOrDefault(pc))
	}

	if failed {
		return errors.New("one or more health checks failed")
	}
	return nil
}

func modelOrDefault(pc config.Provider) string {
	if pc.Model != "" {
		return pc.Model
	}
	switch pc.Kind {
	case config.KindAnthropic:
		return provider.DefaultAnthropicModel
	case config.KindOpenAI:
		return provider.DefaultOpenAIModel
	case config.KindGemini:
		return provider.DefaultGeminiModel
	default:
		return "(none)"
	}
}

// resolveConverseScript makes agent.converse_script absolute (relative paths are relative to the config
// file) and drops it if the file does not exist, so gateway.converse_call is only advertised when it works.
func resolveConverseScript(cfgPath, script string) string {
	if script == "" {
		return ""
	}
	p := script
	if !filepath.IsAbs(p) {
		p = filepath.Join(filepath.Dir(cfgPath), script)
	}
	if st, err := os.Stat(p); err != nil || st.IsDir() {
		return ""
	}
	abs, err := filepath.Abs(p)
	if err != nil {
		return ""
	}
	return abs
}
