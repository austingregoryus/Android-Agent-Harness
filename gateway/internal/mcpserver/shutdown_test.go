package mcpserver

import (
	"context"
	"io"
	"testing"
	"time"

	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/device"
	"github.com/austingregoryus/Android-Agent-Harness/gateway/internal/fakephone"
)

// TestServeReturnsPromptlyOnContextCancelWhileBlockedReading is the
// SIGTERM/SIGINT (and Ctrl+C on Windows) regression: Serve used to read stdin
// with a single-threaded scanner.Scan() call, which blocks on the underlying
// Read until data or EOF arrives. A signal only cancels the context; it does
// not, by itself, unblock a Go-level Read on os.Stdin. With nothing else
// selecting on ctx.Done(), the whole process would hang until the host closed
// stdin, which reads to a host as a server that ignores shutdown.
//
// The fix reads stdin on its own goroutine that feeds a channel; the dispatch
// loop selects on that channel and on ctx.Done(), so cancellation wins even
// while the reader goroutine is still stuck in a blocking Read.
func TestServeReturnsPromptlyOnContextCancelWhileBlockedReading(t *testing.T) {
	p := fakephone.New(fakephone.Options{Token: "tok"})
	defer p.Close()

	dev := device.NewMCPClient(device.MCPOptions{BaseURL: p.URL(), Token: "tok"})
	srv, err := New(Options{Device: dev})
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	// An io.Pipe reader that is never written to and never closed: Read blocks
	// forever, exactly like a stdin the host never closes and never writes to
	// again after the last request.
	inR, inW := io.Pipe()
	defer inW.Close() // avoid leaking the pipe past the test; Serve itself must not depend on this.
	outR, outW := io.Pipe()
	go io.Copy(io.Discard, outR) // drain writes so Serve never blocks on output either.

	ctx, cancel := context.WithCancel(context.Background())

	done := make(chan error, 1)
	go func() { done <- srv.Serve(ctx, inR, outW) }()

	// Give Serve a moment to actually reach its blocking read before cancelling,
	// so the test exercises the "blocked mid-read" case rather than a cancel
	// that beats the goroutine to the starting line.
	time.Sleep(50 * time.Millisecond)
	cancel()

	select {
	case err := <-done:
		if err != context.Canceled {
			t.Errorf("Serve returned %v, want context.Canceled", err)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("Serve did not return within 500ms of context cancellation while blocked reading stdin")
	}
}
