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

import static org.hamcrest.MatcherAssert.assertThat;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

public class SemanticVersionTest {

    @Test
    public void testSemanticVersion() {
        SemanticVersion version = SemanticVersion.parseVersion("1.2.3");
        assertThat(version.toString(), Matchers.equalTo("1.2.3"));
    }

    @Test
    public void testSemanticVersionWithPrefix() {
        SemanticVersion version = SemanticVersion.parseVersion("v1.2.3");
        assertThat(version.toString(), Matchers.equalTo("v1.2.3"));
    }

    @Test
    public void testSemanticVersionWithPreRelease() {
        SemanticVersion version = SemanticVersion.parseVersion("1.2.3-alpha");
        assertThat(version.toString(), Matchers.equalTo("1.2.3-alpha"));
    }

    @Test
    public void testSemanticVersionWithBuild() {
        SemanticVersion version = SemanticVersion.parseVersion("1.2.3+1234");
        assertThat(version.toString(), Matchers.equalTo("1.2.3+1234"));
    }

    @Test
    public void testSemanticVersionWithPreReleaseBuild() {
        SemanticVersion version = SemanticVersion.parseVersion("1.2.3-alpha+1234");
        assertThat(version.toString(), Matchers.equalTo("1.2.3-alpha+1234"));
    }

    @Test
    public void testSemanticVersionWithPrefixPreReleaseBuild() {
        SemanticVersion version = SemanticVersion.parseVersion("v1.2.3-alpha+1234");
        assertThat(version.toString(), Matchers.equalTo("v1.2.3-alpha+1234"));
    }

    @Test
    public void testCompareTo() {
        assertThat(SemanticVersion.parseVersion("v1.0.0").compareTo(
                SemanticVersion.parseVersion("v2.0.0")),
                Matchers.lessThan(0));

        assertThat(SemanticVersion.parseVersion("v2.0.0").compareTo(
                SemanticVersion.parseVersion("v2.1.0")),
                Matchers.lessThan(0));

        assertThat(SemanticVersion.parseVersion("v2.1.0").compareTo(
                SemanticVersion.parseVersion("v2.1.1")),
                Matchers.lessThan(0));

        assertThat(SemanticVersion.parseVersion("v1.2.3").compareTo(
                SemanticVersion.parseVersion("v1.2.3")),
                Matchers.equalTo(0));

        // Build metadata is ignored
        assertThat(SemanticVersion.parseVersion("v1.2.3-alpha+102030").compareTo(
                SemanticVersion.parseVersion("v1.2.3-alpha+405060")),
                Matchers.equalTo(0));

        // 1.0.0-alpha < 1.0.0-alpha.1 < 1.0.0-alpha.beta < 1.0.0-beta < 1.0.0-beta.2 < 1.0.0-beta.11 < 1.0.0-rc.1 < 1.0.0
        assertThat(SemanticVersion.parseVersion("1.0.0-alpha").compareTo(
                SemanticVersion.parseVersion("1.0.0-alpha.1")), Matchers.lessThan(0));

        assertThat(SemanticVersion.parseVersion("1.0.0-alpha.1").compareTo(
                SemanticVersion.parseVersion("1.0.0-alpha.beta")), Matchers.lessThan(0));

        assertThat(SemanticVersion.parseVersion("1.0.0-alpha.beta").compareTo(
                SemanticVersion.parseVersion("1.0.0-beta")), Matchers.lessThan(0));

        assertThat(SemanticVersion.parseVersion("1.0.0-beta").compareTo(
                SemanticVersion.parseVersion("1.0.0-beta.2")), Matchers.lessThan(0));

        assertThat(SemanticVersion.parseVersion("1.0.0-beta.2").compareTo(
                SemanticVersion.parseVersion("1.0.0-beta.11")), Matchers.lessThan(0));

        assertThat(SemanticVersion.parseVersion("1.0.0-beta.11").compareTo(
                SemanticVersion.parseVersion("1.0.0-rc.1")), Matchers.lessThan(0));

        assertThat(SemanticVersion.parseVersion("1.0.0-rc.1").compareTo(
                SemanticVersion.parseVersion("1.0.0")), Matchers.lessThan(0));

        // Reversed direction
        assertThat(SemanticVersion.parseVersion("1.0.0-alpha.1").compareTo(
                SemanticVersion.parseVersion("1.0.0-alpha")), Matchers.greaterThan(0));

        assertThat(SemanticVersion.parseVersion("1.0.0-beta").compareTo(
                SemanticVersion.parseVersion("1.0.0-alpha.alpha.1")), Matchers.greaterThan(0));

        assertThat(SemanticVersion.parseVersion("1.0.0-beta").compareTo(
                SemanticVersion.parseVersion("1.0.0-alpha.beta")), Matchers.greaterThan(0));

        assertThat(SemanticVersion.parseVersion("1.0.0-beta.2").compareTo(
                SemanticVersion.parseVersion("1.0.0-beta")), Matchers.greaterThan(0));

        assertThat(SemanticVersion.parseVersion("1.0.0-beta.11").compareTo(
                SemanticVersion.parseVersion("1.0.0-beta.2")), Matchers.greaterThan(0));

        assertThat(SemanticVersion.parseVersion("1.0.0-rc.1").compareTo(
                SemanticVersion.parseVersion("1.0.0-beta.11")), Matchers.greaterThan(0));

        assertThat(SemanticVersion.parseVersion("1.0.0").compareTo(
                SemanticVersion.parseVersion("1.0.0-rc.1")), Matchers.greaterThan(0));
    }

    @Test
    public void testCompareToWithGoPseudoVersions() {
        assertThat(SemanticVersion.parseVersion("v1.2.3-20200518203908-8018eb2c26ba").compareTo(
                SemanticVersion.parseVersion("v1.2.3-20191204190536-9bdfabe68543")), Matchers.greaterThan(0));
    }
}
