package device

import "github.com/austingregoryus/Android-Agent-Harness/gateway/internal/canonicaljson"

// canonicalJSON renders v with sorted keys and no insignificant whitespace, the
// form protocol.md requires inside the idempotency key hash.
func canonicalJSON(v any) ([]byte, error) { return canonicaljson.Marshal(v) }
