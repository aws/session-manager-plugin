package smithyoteltracing

import (
	"fmt"
	"testing"

	"github.com/aws/smithy-go/tracing"
	otelcodes "go.opentelemetry.io/otel/codes"
	oteltrace "go.opentelemetry.io/otel/trace"
)

func TestToOTELSpanKind(t *testing.T) {
	for _, tt := range []struct {
		In     tracing.SpanKind
		Expect oteltrace.SpanKind
	}{
		{tracing.SpanKindClient, oteltrace.SpanKindClient},
		{tracing.SpanKindServer, oteltrace.SpanKindServer},
		{tracing.SpanKindProducer, oteltrace.SpanKindProducer},
		{tracing.SpanKindConsumer, oteltrace.SpanKindConsumer},
		{tracing.SpanKindInternal, oteltrace.SpanKindInternal},
		{tracing.SpanKind(-1), oteltrace.SpanKindInternal},
	} {
		name := fmt.Sprintf("%v -> %v", tt.In, tt.Expect)
		t.Run(name, func(t *testing.T) {
			actual := toOTELSpanKind(tt.In)
			if tt.Expect != actual {
				t.Errorf("%v != %v", tt.Expect, actual)
			}
		})
	}
}

func TestToOTELSpanStatus(t *testing.T) {
	for _, tt := range []struct {
		In     tracing.SpanStatus
		Expect otelcodes.Code
	}{
		{tracing.SpanStatusOK, otelcodes.Ok},
		{tracing.SpanStatusError, otelcodes.Error},
		{tracing.SpanStatusUnset, otelcodes.Unset},
		{tracing.SpanStatus(-1), otelcodes.Unset},
	} {
		name := fmt.Sprintf("%v -> %v", tt.In, tt.Expect)
		t.Run(name, func(t *testing.T) {
			actual := toOTELSpanStatus(tt.In)
			if tt.Expect != actual {
				t.Errorf("%v != %v", tt.Expect, actual)
			}
		})
	}
}
