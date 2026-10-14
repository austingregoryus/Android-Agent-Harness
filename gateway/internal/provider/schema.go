package provider

import "strings"

// openAIUnsupported lists JSON Schema keywords that OpenAI-compatible function
// definitions reject or silently mishandle. They are stripped recursively.
var openAIUnsupported = map[string]bool{
	"$schema":               true,
	"$id":                   true,
	"$comment":              true,
	"$anchor":               true,
	"$defs":                 true,
	"definitions":           true,
	"$ref":                  true,
	"examples":              true,
	"contentMediaType":      true,
	"contentEncoding":       true,
	"if":                    true,
	"then":                  true,
	"else":                  true,
	"not":                   true,
	"patternProperties":     true,
	"propertyNames":         true,
	"dependentSchemas":      true,
	"dependentRequired":     true,
	"unevaluatedProperties": true,
	"unevaluatedItems":      true,
	"readOnly":              true,
	"writeOnly":             true,
	"deprecated":            true,
}

// SanitizeOpenAISchema returns a copy of a JSON Schema with keywords the
// OpenAI function-calling surface does not support removed. The original map is
// not modified.
func SanitizeOpenAISchema(schema map[string]any) map[string]any {
	out := sanitizeMap(schema, func(k string) bool { return !openAIUnsupported[k] }, sanitizeOpenAIValue)
	if _, ok := out["type"]; !ok {
		out["type"] = "object"
	}
	if out["type"] == "object" {
		if _, ok := out["properties"]; !ok {
			out["properties"] = map[string]any{}
		}
	}
	return out
}

func sanitizeOpenAIValue(key string, v any) any {
	return sanitizeValue(v, SanitizeOpenAISchema, key)
}

// anthropicUnsupported lists JSON Schema keywords the Messages API's
// input_schema rejects. Reference keywords are the dangerous ones: the runtime
// may emit "$ref"/"$defs" for a shared sub-schema, and a single unresolvable
// reference 400s every turn of the task, not just the call that uses the tool.
var anthropicUnsupported = map[string]bool{
	"$schema":     true,
	"$id":         true,
	"$ref":        true,
	"$defs":       true,
	"$anchor":     true,
	"$comment":    true,
	"definitions": true,
}

// SanitizeAnthropicSchema returns a copy of a JSON Schema fit for the Messages
// API's tools[].input_schema: reference and metadata keywords are stripped
// recursively, and the root is forced to a well-formed object schema. A nil or
// non-object schema becomes the empty object schema. The original map is not
// modified.
func SanitizeAnthropicSchema(schema map[string]any) map[string]any {
	if schema == nil {
		return map[string]any{"type": "object", "properties": map[string]any{}}
	}
	return sanitizeAnthropicSchema(schema, true)
}

func sanitizeAnthropicSchema(schema map[string]any, root bool) map[string]any {
	out := sanitizeMap(schema, func(k string) bool { return !anthropicUnsupported[k] }, sanitizeAnthropicValue)
	if !root {
		// A nested sub-schema keeps whatever type it declared. Stamping
		// "object" onto a typeless or scalar node would rewrite the argument
		// contract the phone published.
		return out
	}
	// The API requires the top level to be an object schema with properties.
	if t, ok := out["type"].(string); !ok || t != "object" {
		out["type"] = "object"
	}
	if _, ok := out["properties"].(map[string]any); !ok {
		out["properties"] = map[string]any{}
	}
	return out
}

func sanitizeAnthropicValue(key string, v any) any {
	return sanitizeValue(v, func(m map[string]any) map[string]any {
		return sanitizeAnthropicSchema(m, false)
	}, key)
}

// geminiAllowed lists the OpenAPI 3.0 subset Gemini's Schema type accepts for
// function declarations. Everything else is dropped.
var geminiAllowed = map[string]bool{
	"type":        true,
	"format":      true,
	"description": true,
	"nullable":    true,
	"enum":        true,
	"items":       true,
	"properties":  true,
	"required":    true,
	"minItems":    true,
	"maxItems":    true,
	"minimum":     true,
	"maximum":     true,
	"anyOf":       true,
	"title":       true,
}

// geminiTypes maps JSON Schema type names to Gemini's Type enum values.
var geminiTypes = map[string]string{
	"string":  "STRING",
	"number":  "NUMBER",
	"integer": "INTEGER",
	"boolean": "BOOLEAN",
	"array":   "ARRAY",
	"object":  "OBJECT",
	"null":    "STRING",
}

// geminiFormats lists the format values Gemini's Schema type understands, keyed
// by the type they may appear on. Any other format (email, uri, uuid, ipv4, ...)
// is dropped: Gemini rejects the declaration rather than ignoring the keyword.
var geminiFormats = map[string]map[string]bool{
	"STRING":  {"date-time": true, "enum": true},
	"INTEGER": {"int32": true, "int64": true},
	"NUMBER":  {"float": true, "double": true},
}

// SanitizeGeminiSchema returns a copy of a JSON Schema reduced to the OpenAPI
// subset Gemini accepts in functionDeclarations, with type names normalised to
// Gemini's uppercase Type enum. The original map is not modified.
//
// Only the root is forced to be an object: a nested sub-schema that legitimately
// omits "type" (an enum-only or description-only node) must keep its own shape,
// and stamping OBJECT plus an empty "properties" onto it corrupts the argument
// contract the phone published.
func SanitizeGeminiSchema(schema map[string]any) map[string]any {
	return sanitizeGeminiSchema(schema, true)
}

func sanitizeGeminiSchema(schema map[string]any, root bool) map[string]any {
	out := sanitizeMap(schema, func(k string) bool { return geminiAllowed[k] }, sanitizeGeminiValue)
	if t, ok := out["type"].(string); ok {
		if mapped, ok := geminiTypes[strings.ToLower(t)]; ok {
			out["type"] = mapped
		}
	} else if root {
		out["type"] = "OBJECT"
	}
	if root && out["type"] == "OBJECT" {
		if _, ok := out["properties"]; !ok {
			out["properties"] = map[string]any{}
		}
	}
	// A format is only legal on the type that defines it.
	if f, ok := out["format"].(string); ok {
		t, _ := out["type"].(string)
		if !geminiFormats[t][f] {
			delete(out, "format")
		}
	} else if _, present := out["format"]; present {
		delete(out, "format")
	}
	return out
}

func sanitizeGeminiValue(key string, v any) any {
	return sanitizeValue(v, func(m map[string]any) map[string]any {
		return sanitizeGeminiSchema(m, false)
	}, key)
}

// sanitizeValue recurses into nested schemas. Keys whose values are themselves
// schemas ("items", entries of "properties", entries of "anyOf") are rewritten
// with fn; everything else is deep-copied unchanged.
func sanitizeValue(v any, fn func(map[string]any) map[string]any, key string) any {
	switch key {
	case "properties":
		m, ok := v.(map[string]any)
		if !ok {
			return deepCopy(v)
		}
		out := make(map[string]any, len(m))
		for name, sub := range m {
			if subMap, ok := sub.(map[string]any); ok {
				out[name] = fn(subMap)
			} else {
				out[name] = deepCopy(sub)
			}
		}
		return out
	case "items":
		if m, ok := v.(map[string]any); ok {
			return fn(m)
		}
		return deepCopy(v)
	case "anyOf", "oneOf", "allOf":
		arr, ok := v.([]any)
		if !ok {
			return deepCopy(v)
		}
		out := make([]any, 0, len(arr))
		for _, e := range arr {
			if m, ok := e.(map[string]any); ok {
				out = append(out, fn(m))
			} else {
				out = append(out, deepCopy(e))
			}
		}
		return out
	default:
		return deepCopy(v)
	}
}

func sanitizeMap(m map[string]any, keep func(string) bool, val func(string, any) any) map[string]any {
	out := make(map[string]any, len(m))
	for k, v := range m {
		if !keep(k) {
			continue
		}
		out[k] = val(k, v)
	}
	return out
}

func deepCopy(v any) any {
	switch t := v.(type) {
	case map[string]any:
		out := make(map[string]any, len(t))
		for k, e := range t {
			out[k] = deepCopy(e)
		}
		return out
	case []any:
		out := make([]any, len(t))
		for i, e := range t {
			out[i] = deepCopy(e)
		}
		return out
	default:
		return v
	}
}
