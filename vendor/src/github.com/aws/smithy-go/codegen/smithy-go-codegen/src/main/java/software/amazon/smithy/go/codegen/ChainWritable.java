package software.amazon.smithy.go.codegen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import software.amazon.smithy.utils.ListUtils;

/**
 * Chains together multiple Writables that can be composed into one Writable.
 */
public final class ChainWritable {
    private final List<Writable> writables;

    public ChainWritable() {
        writables = new ArrayList<>();
    }

    public static ChainWritable of(Writable... writables) {
        var chain = new ChainWritable();
        chain.writables.addAll(ListUtils.of(writables));
        return chain;
    }

    public static ChainWritable of(Collection<Writable> writables) {
        var chain = new ChainWritable();
        chain.writables.addAll(writables);
        return chain;
    }

    public boolean isEmpty() {
        return writables.isEmpty();
    }

    public ChainWritable add(Writable writable) {
        writables.add(writable);
        return this;
    }

    public <T> ChainWritable add(Optional<T> value, Function<T, Writable> fn) {
        value.ifPresent(t -> writables.add(fn.apply(t)));
        return this;
    }

    public ChainWritable add(boolean include, Writable writable) {
        if (!include) {
            writables.add(writable);
        }
        return this;
    }

    public Writable compose(boolean writeNewlines) {
        return (GoWriter writer) -> {
            var hasPrevious = false;
            for (Writable writable : writables) {
                if (hasPrevious && writeNewlines) {
                    writer.write("");
                }
                hasPrevious = true;
                writer.write("$W", writable);
            }
        };
    }

    public Writable compose() {
        return compose(true);
    }
}
