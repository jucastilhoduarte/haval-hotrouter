package com.castilhoduarte.jlh6;

public interface Shell {
    ShellResult exec(String command);

    final class ShellResult {
        public final String output;
        public final int exitCode;
        public ShellResult(String output, int exitCode) { this.output = output; this.exitCode = exitCode; }
        public boolean ok() { return exitCode == 0; }
    }
}
