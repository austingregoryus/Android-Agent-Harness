package provider

import (
	"crypto/sha256"
	"encoding/hex"
	"strings"
)

// aliasToolNames keeps canonical phone names in the transcript while presenting
// API-safe function names to OpenAI and Anthropic. Dots are illegal in both
// providers' tool definitions. The digest distinguishes names whose readable
// prefixes become identical after sanitizing or truncating.
func aliasToolNames(req GenerateRequest) (GenerateRequest, map[string]string) {
	reverse := map[string]string{}
	alias := func(name string) string {
		sum := sha256.Sum256([]byte(name))
		var prefix strings.Builder
		for _, c := range name {
			if prefix.Len() >= 35 {
				break
			}
			if c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-' {
				prefix.WriteByte(byte(c))
			} else {
				prefix.WriteByte('_')
			}
		}
		wire := "sym_" + prefix.String() + "_" + hex.EncodeToString(sum[:12])
		reverse[wire] = name
		return wire
	}
	req.Tools = append([]ToolDef(nil), req.Tools...)
	for i := range req.Tools {
		req.Tools[i].Name = alias(req.Tools[i].Name)
	}
	req.Messages = append([]Message(nil), req.Messages...)
	for i := range req.Messages {
		req.Messages[i].Parts = append([]Part(nil), req.Messages[i].Parts...)
		for j := range req.Messages[i].Parts {
			p := &req.Messages[i].Parts[j]
			if p.Type == PartToolCall {
				p.Name = alias(p.Name)
			}
		}
	}
	return req, reverse
}

func restoreToolNames(result GenerateResult, reverse map[string]string) GenerateResult {
	for i := range result.Message.Parts {
		p := &result.Message.Parts[i]
		if p.Type == PartToolCall {
			if canonical, ok := reverse[p.Name]; ok {
				p.Name = canonical
			}
		}
	}
	return result
}
