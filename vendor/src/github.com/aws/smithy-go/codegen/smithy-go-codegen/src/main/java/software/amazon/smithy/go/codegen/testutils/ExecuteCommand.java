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

package software.amazon.smithy.go.codegen.testutils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import software.amazon.smithy.utils.SmithyBuilder;

/**
 * Utility for invoking command line utilities.
 */
public final class ExecuteCommand {
    private static final Logger LOGGER = Logger.getLogger(ExecuteCommand.class.getName());

    private final List<String> command;
    private final File workingDir;

    private ExecuteCommand(Builder builder) {
        command = SmithyBuilder.requiredState("command", builder.command);
        workingDir = builder.workingDir;
    }

    /**
     * Invokes the command in the filepath directory provided.
     * @param workingDir Directory to execute the command in.
     * @param command Command to be executed.
     * @throws Exception if the command fails.
     */
    public static void execute(File workingDir, String... command) throws Exception {
        ExecuteCommand.builder()
                .addCommand(command)
                .workingDir(workingDir)
                .build()
                .execute();
    }

    /**
     * Invokes the command returning the exception if there was any.
     * @throws Exception if the command fails.
     */
    public void execute() throws Exception {
        int exitCode;
        Process child;
        var output = new StringBuilder();
        try {
            var cmdArray = new String[command.size()];
            command.toArray(cmdArray);

            child = Runtime.getRuntime().exec(cmdArray, null, workingDir);
            exitCode = child.waitFor();

            BufferedReader stdOut = new BufferedReader(new
                    InputStreamReader(child.getInputStream(), Charset.defaultCharset()));

            BufferedReader stdErr = new BufferedReader(new
                    InputStreamReader(child.getErrorStream(), Charset.defaultCharset()));

            String s;
            output.append("stdout: ");
            while ((s = stdOut.readLine()) != null) {
                LOGGER.info(s);
                output.append(s);
            }
            stdOut.close();
            output.append(" stderr: ");
            while ((s = stdErr.readLine()) != null) {
                LOGGER.warning(s);
                output.append(s);
            }
            stdErr.close();
        } catch (Exception e) {
            throw new Exception("Unable to execute command, " + command, e);
        }

        if (exitCode != 0) {
            throw new Exception("Command existed with non-zero code, " + command
                    + ", status code: " + exitCode + " output: " + output);
        }
    }

    /**
     * Returns the builder for ExecuteCommand.
     * @return ExecuteCommand builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * ExecuteCommand builder.
     */
    public static final class Builder implements SmithyBuilder<ExecuteCommand> {
        private List<String> command;
        private File workingDir;

        private Builder() {
        }

        /**
         * Adds command arguments to the set of arguments the command will be executed with.
         * @param command command and arguments to be executed.
         * @return builder
         */
        public Builder addCommand(String... command) {
            if (this.command == null) {
                this.command = new ArrayList<>();
            }

            this.command.addAll(List.of(command));
            return this;
        }

        /**
         * Sets the working directory for the command.
         * @param workingDir working directory.
         * @return builder
         */
        public Builder workingDir(File workingDir) {
            this.workingDir = workingDir;
            return this;
        }

        /**
         * Builds the ExecuteCommand.
         * @return Execute command
         */
        @Override
        public ExecuteCommand build() {
            return new ExecuteCommand(this);
        }
    }
}
