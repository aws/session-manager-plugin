/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.go.codegen;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.smithy.utils.SmithyBuilder;

/**
 * A semantic version parser that allows for prefixes to be compatible with Go version tags.
 */
public final class SemanticVersion {
    // Regular Expression from https://semver.org/#is-there-a-suggested-regular-expression-regex-to-check-a-semver-string
    private static final Pattern SEMVER_PATTERN = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
            + "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?"
            + "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");

    private final String prefix;
    private final int major;
    private final int minor;
    private final int patch;
    private final String preRelease;
    private final String build;

    private SemanticVersion(Builder builder) {
        prefix = builder.prefix;
        major = builder.major;
        minor = builder.minor;
        patch = builder.patch;
        preRelease = builder.preRelease;
        build = builder.build;
    }

    /**
     * The semantic version prefix present before the major version.
     *
     * @return the optional prefix
     */
    public Optional<String> getPrefix() {
        return Optional.ofNullable(prefix);
    }

    /**
     * The major version number.
     *
     * @return the major version
     */
    public int getMajor() {
        return major;
    }

    /**
     * The minor version number.
     *
     * @return the minor version
     */
    public int getMinor() {
        return minor;
    }

    /**
     * The patch version number.
     *
     * @return the patch version
     */
    public int getPatch() {
        return patch;
    }

    public Optional<String> getPreRelease() {
        return Optional.ofNullable(preRelease);
    }

    public Optional<String> getBuild() {
        return Optional.ofNullable(build);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();

        if (getPrefix().isPresent()) {
            builder.append(getPrefix().get());
        }

        builder.append(getMajor());
        builder.append('.');
        builder.append(getMinor());
        builder.append('.');
        builder.append(getPatch());
        if (getPreRelease().isPresent()) {
            builder.append('-');
            builder.append(getPreRelease().get());
        }
        if (getBuild().isPresent()) {
            builder.append('+');
            builder.append(getBuild().get());
        }

        return builder.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SemanticVersion that = (SemanticVersion) o;
        return getMajor() == that.getMajor()
                && getMinor() == that.getMinor()
                && getPatch() == that.getPatch()
                && getPrefix().equals(that.getPrefix())
                && getPreRelease().equals(that.getPreRelease())
                && getBuild().equals(that.getBuild());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getPrefix(), getMajor(), getMinor(), getPatch(), getPreRelease(), getBuild());
    }

    /**
     * Parse a semantic version string into a {@link SemanticVersion}.
     *
     * @param version the semantic version string
     * @return the SemanticVersion representing the parsed value
     */
    public static SemanticVersion parseVersion(String version) {
        char[] parseArr = version.toCharArray();
        StringBuilder prefixBuilder = new StringBuilder();
        int position = 0;
        while (position < parseArr.length && !Character.isDigit(parseArr[position])) {
            prefixBuilder.append(parseArr[position]);
            position++;
        }

        String prefix = null;
        if (prefixBuilder.length() > 0) {
            prefix = prefixBuilder.toString();
        }

        Matcher matcher = SEMVER_PATTERN.matcher(version.substring(position));

        if (!matcher.matches()) {
            throw newInvalidSemanticVersion(version);
        }

        return builder()
                .prefix(prefix)
                .major(Integer.parseInt(matcher.group(1)))
                .minor(Integer.parseInt(matcher.group(2)))
                .patch(Integer.parseInt(matcher.group(3)))
                .preRelease(matcher.group(4))
                .build(matcher.group(5))
                .build();
    }

    private static IllegalArgumentException newInvalidSemanticVersion(String version) {
        return new IllegalArgumentException("Invalid semantic version string: " + version);
    }

    /**
     * Get a {@link SemanticVersion} builder.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Return a builder for this {@link SemanticVersion}.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return builder()
                .prefix(this.prefix)
                .major(this.major)
                .minor(this.minor)
                .patch(this.patch)
                .preRelease(this.preRelease)
                .build(this.build);
    }

    /**
     * Compare two {@link SemanticVersion}, ignoring prefix strings. To validate that prefix strings match
     * see the overloaded function signature.
     *
     * @param o the {@link SemanticVersion} to be compared.
     * @return the value {@code 0} if this {@code SemanticVersion} is
     * equal to the argument {@code SemanticVersion}; a value less than
     * {@code 0} if this {@code SemanticVersion} is less
     * than the argument {@code SemanticVersion}; and a value greater
     * than {@code 0} if this {@code SemanticVersion} is
     * greater than the argument {@code SemanticVersion}.
     */
    public int compareTo(SemanticVersion o) {
        return compareTo(o, (o1, o2) -> 0);
    }

    /**
     * Compare two {@link SemanticVersion}, using the prefixComparator for comparing the prefix strings.
     *
     * @param o                the {@link SemanticVersion} to be compared.
     * @param prefixComparator the comparator for comparing prefixes
     * @return the value {@code 0} if this {@code SemanticVersion} is
     * equal to the argument {@code SemanticVersion}; a value less than
     * {@code 0} if this {@code SemanticVersion} is less
     * than the argument {@code SemanticVersion}; and a value greater
     * than {@code 0} if this {@code SemanticVersion} is
     * greater than the argument {@code SemanticVersion}.
     */
    public int compareTo(
            SemanticVersion o,
            Comparator<Optional<String>> prefixComparator
    ) {
        int cmp = prefixComparator.compare(getPrefix(), o.getPrefix());
        if (cmp != 0) {
            return cmp;
        }

        cmp = Integer.compare(getMajor(), o.getMajor());
        if (cmp != 0) {
            return cmp;
        }

        cmp = Integer.compare(getMinor(), o.getMinor());
        if (cmp != 0) {
            return cmp;
        }

        cmp = Integer.compare(getPatch(), o.getPatch());
        if (cmp != 0) {
            return cmp;
        }

        if (!getPreRelease().isPresent() && !o.getPreRelease().isPresent()) {
            return 0;
        }

        if (!getPreRelease().isPresent()) {
            return 1;
        }

        if (!o.getPreRelease().isPresent()) {
            return -1;
        }

        return comparePreRelease(getPreRelease().get(), o.getPreRelease().get());
    }

    private static int comparePreRelease(String x, String y) {
        String[] xIdentifiers = x.split("\\.");
        String[] yIdentifiers = y.split("\\.");

        int cmp = 0;
        int xPos = 0;
        int yPos = 0;

        while (xPos < xIdentifiers.length && yPos < yIdentifiers.length && cmp == 0) {
            Optional<Integer> xInt = parsePositiveInteger(xIdentifiers[xPos]);
            Optional<Integer> yInt = parsePositiveInteger(yIdentifiers[yPos]);

            if (xInt.isPresent() && yInt.isPresent()) {
                cmp = Integer.compare(xInt.get(), yInt.get());
                continue;
            }

            if (xInt.isPresent()) {
                cmp = -1;
                continue;
            }

            if (yInt.isPresent()) {
                cmp = 1;
                continue;
            }

            cmp = xIdentifiers[xPos].compareTo(yIdentifiers[yPos]);

            xPos++;
            yPos++;
        }

        if (cmp != 0) {
            return cmp;
        }

        int xRemaining = xIdentifiers.length - 1 - xPos;
        int yRemaining = yIdentifiers.length - 1 - yPos;

        if (xRemaining == yRemaining) {
            return 0;
        }

        return (xRemaining < yRemaining) ? -1 : 1;
    }

    private static Optional<Integer> parsePositiveInteger(String value) {
        try {
            int i = Integer.parseInt(value);

            if (i < 0) {
                return Optional.empty();
            }

            return Optional.of(i);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Builder for {@link SemanticVersion}.
     */
    public static final class Builder implements SmithyBuilder<SemanticVersion> {
        private String prefix;
        private int major;
        private int minor;
        private int patch;
        private String preRelease;
        private String build;

        private Builder() {
        }

        public Builder prefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        public Builder major(int major) {
            this.major = major;
            return this;
        }

        public Builder minor(int minor) {
            this.minor = minor;
            return this;
        }

        public Builder patch(int patch) {
            this.patch = patch;
            return this;
        }

        public Builder preRelease(String preRelease) {
            this.preRelease = preRelease;
            return this;
        }

        public Builder build(String build) {
            this.build = build;
            return this;
        }

        @Override
        public SemanticVersion build() {
            return new SemanticVersion(this);
        }
    }
}
