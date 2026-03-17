package software.amazon.smithy.go.codegen;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

import org.junit.jupiter.api.Test;

public class GoStackStepMiddlewareGeneratorTest {
    @Test
    public void generatesSerializeMiddlewareDefinition() {
        GoWriter writer = new GoWriter("middlewaregentest");

        GoStackStepMiddlewareGenerator.createSerializeStepMiddleware("someMiddleware",
                MiddlewareIdentifier.string("some id"))
                .writeMiddleware(writer, (m, w) -> {
                    w.openBlock("return next.$L(ctx, in)", m.getHandleMethodName());
                });

        String generated = writer.toString();

        System.out.println(generated);
        assertThat(generated, containsString("type someMiddleware struct {"));
        assertThat(generated, containsString("func (*someMiddleware) ID() string {"));
        assertThat(generated, containsString("return \"some id\""));
        assertThat(generated, containsString("func (m *someMiddleware) HandleSerialize(" +
                "ctx context.Context, in middleware.SerializeInput, next middleware.SerializeHandler) ("));
        assertThat(generated, containsString("out middleware.SerializeOutput, metadata middleware.Metadata, err error,"));
        assertThat(generated, containsString("return next.HandleSerialize(ctx, in)"));
    }
}
