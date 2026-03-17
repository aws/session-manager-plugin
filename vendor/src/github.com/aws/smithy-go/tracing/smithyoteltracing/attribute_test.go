package smithyoteltracing

import (
	"fmt"
	"testing"

	otelattribute "go.opentelemetry.io/otel/attribute"
)

type stringer struct{}

func (s stringer) String() string {
	return "stringer"
}

type notstringer struct{}

func TestToOTELKeyValue(t *testing.T) {
	for _, tt := range []struct {
		K, V   any
		Expect otelattribute.KeyValue
	}{
		{1, "asdf", otelattribute.String("1", "asdf")},               // non-string key
		{"key", stringer{}, otelattribute.String("key", "stringer")}, // stringer
		// unsupported value type
		{"key", notstringer{}, otelattribute.String("key", "smithyoteltracing.notstringer{}")},
		{"key", true, otelattribute.Bool("key", true)},
		{"key", []bool{true, false}, otelattribute.BoolSlice("key", []bool{true, false})},
		{"key", int(1), otelattribute.Int("key", 1)},
		{"key", []int{1, 2}, otelattribute.IntSlice("key", []int{1, 2})},
		{"key", int64(1), otelattribute.Int64("key", 1)},
		{"key", []int64{1, 2}, otelattribute.Int64Slice("key", []int64{1, 2})},
		{"key", float64(1), otelattribute.Float64("key", 1)},
		{"key", []float64{1, 2}, otelattribute.Float64Slice("key", []float64{1, 2})},
		{"key", "value", otelattribute.String("key", "value")},
		{"key", []string{"v1", "v2"}, otelattribute.StringSlice("key", []string{"v1", "v2"})},
	} {
		name := fmt.Sprintf("(%v, %v) -> %v", tt.K, tt.V, tt.Expect)
		t.Run(name, func(t *testing.T) {
			actual := toOTELKeyValue(tt.K, tt.V)
			if tt.Expect != actual {
				t.Errorf("%v != %v", tt.Expect, actual)
			}
		})
	}
}
