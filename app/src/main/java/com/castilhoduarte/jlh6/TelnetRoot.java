package com.castilhoduarte.jlh6;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TelnetRoot implements AutoCloseable {

    public static final String HOST = "127.0.0.1";
    public static final int PORT = 23;

    private static final int IAC = 255;
    private static final int DONT = 254;
    private static final int DO = 253;
    private static final int WONT = 252;
    private static final int WILL = 251;
    private static final int SB = 250;
    private static final int SE = 240;

    static final String BEG = "__HR_BEG__";
    static final String END = "__HR_END__";
    private static final Pattern END_LINE = Pattern.compile("^" + END + "(-?\\d+)$");
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d?]*[ -/]*[@-~]");

    public static final class Result {
        public final String output;
        public final int exitCode;

        Result(String output, int exitCode) {
            this.output = output;
            this.exitCode = exitCode;
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final int readTimeoutMs;

    public TelnetRoot(int connectTimeoutMs, int readTimeoutMs) throws IOException {
        this.readTimeoutMs = readTimeoutMs;
        socket = new Socket();
        socket.connect(new InetSocketAddress(HOST, PORT), connectTimeoutMs);
        socket.setSoTimeout(readTimeoutMs);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    public Result exec(String command) throws IOException {
        String line = "echo " + BEG + "; ( " + command + " ); echo " + END + "$?\n";
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.flush();

        StringBuilder text = new StringBuilder();
        byte[] buf = new byte[2048];
        long deadline = monoNow() + readTimeoutMs;

        while (monoNow() < deadline) {
            int n;
            try {
                n = in.read(buf, 0, buf.length);
            } catch (SocketTimeoutException e) {
                continue;
            }
            if (n < 0) break;
            consume(buf, n, out, text);
            Result r = extract(text.toString());
            if (r != null) return r;
        }
        throw new IOException("telnet: timed out; got: " + stripNoise(text.toString()));
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) {}
    }

    private static long monoNow() {
        return System.nanoTime() / 1_000_000L;
    }

    static void consume(byte[] buf, int len, OutputStream reply, StringBuilder text)
            throws IOException {
        int i = 0;
        while (i < len) {
            int b = buf[i] & 0xFF;
            if (b != IAC) {
                text.append((char) b);
                i++;
                continue;
            }
            if (i + 1 >= len) break;
            int cmd = buf[i + 1] & 0xFF;
            if (cmd == IAC) {
                text.append((char) IAC);
                i += 2;
            } else if (cmd == WILL || cmd == DO || cmd == WONT || cmd == DONT) {
                if (i + 2 >= len) break;
                int opt = buf[i + 2] & 0xFF;
                if (reply != null) {
                    int response = (cmd == WILL || cmd == WONT) ? DONT : WONT;
                    reply.write(new byte[]{(byte) IAC, (byte) response, (byte) opt});
                    reply.flush();
                }
                i += 3;
            } else if (cmd == SB) {
                int j = i + 2;
                while (j + 1 < len && !((buf[j] & 0xFF) == IAC && (buf[j + 1] & 0xFF) == SE)) j++;
                i = j + 2;
            } else {
                i += 2;
            }
        }
    }

    static String stripNoise(String s) {
        return ANSI.matcher(s).replaceAll("").replace("\r", "");
    }

    static Result extract(String raw) {
        String clean = stripNoise(raw);
        String[] lines = clean.split("\n", -1);

        int begIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals(BEG)) { begIdx = i; break; }
        }
        if (begIdx < 0) return null;

        for (int i = begIdx + 1; i < lines.length; i++) {
            Matcher m = END_LINE.matcher(lines[i].trim());
            if (m.matches()) {
                StringBuilder body = new StringBuilder();
                for (int k = begIdx + 1; k < i; k++) {
                    if (body.length() > 0) body.append('\n');
                    body.append(lines[k]);
                }
                int code;
                try { code = Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { code = -1; }
                return new Result(body.toString().trim(), code);
            }
        }
        return null;
    }
}
