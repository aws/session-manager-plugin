package software.amazon.smithy.go.codegen;

import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.build.SmithyBuildPlugin;

public class ShapeCodegenPlugin implements SmithyBuildPlugin {
    @Override
    public String getName() {
        return "go-shape-codegen";
    }

    @Override
    public void execute(PluginContext context) {
        AbstractDirectedCodegen.run(context, new ShapeDirectedCodegen());
    }
}
