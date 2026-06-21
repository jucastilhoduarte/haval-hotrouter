package com.castilhoduarte.jlh6;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class TelnetRootTest {

    static int passed = 0, failed = 0;

    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("  ok   " + name); }
        else { failed++; System.out.println("  FAIL " + name); }
    }

    static TelnetRoot.Result run(String serverBytes) throws IOException {
        byte[] b = serverBytes.getBytes("ISO-8859-1");
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        StringBuilder text = new StringBuilder();
        TelnetRoot.consume(b, b.length, reply, text);
        return TelnetRoot.extract(text.toString());
    }

    public static void main(String[] args) throws Exception {
        // 1. Shell com eco ativo
        String echoed = ":/ # echo __HR_BEG__; ( cat /x ); echo __HR_END__$?\r\n"
                + "__HR_BEG__\r\nWLAN|1718700000\r\n__HR_END__0\r\n:/ # ";
        TelnetRoot.Result r1 = run(echoed);
        check("echo-on: not null", r1 != null);
        check("echo-on: output", r1 != null && r1.output.equals("WLAN|1718700000"));
        check("echo-on: exit 0", r1 != null && r1.exitCode == 0);

        // 2. Shell sem eco
        TelnetRoot.Result r2 = run("__HR_BEG__\r\nhello world\r\n__HR_END__0\r\n");
        check("no-echo: output", r2 != null && r2.output.equals("hello world"));

        // 3. Saída multilinha
        TelnetRoot.Result r3 = run("__HR_BEG__\r\nline1\r\nline2\r\nline3\r\n__HR_END__0\r\n");
        check("multiline", r3 != null && r3.output.equals("line1\nline2\nline3"));

        // 4. Código de saída != 0
        TelnetRoot.Result r4 = run("__HR_BEG__\r\n__HR_END__7\r\n");
        check("nonzero exit", r4 != null && r4.exitCode == 7 && r4.output.isEmpty());

        // 5. Bloco incompleto retorna null
        TelnetRoot.Result r5 = run("__HR_BEG__\r\npartial output\r\n");
        check("incomplete -> null", r5 == null);

        // 6. Sequências ANSI removidas
        TelnetRoot.Result r6 = run("__HR_BEG__\r\n[1;32mgreen[0m\r\n__HR_END__0\r\n");
        check("ansi stripped", r6 != null && r6.output.equals("green"));

        // 7. Negociação IAC
        StringBuilder sb = new StringBuilder();
        sb.append((char) 255).append((char) 251).append((char) 1);
        sb.append((char) 255).append((char) 253).append((char) 3);
        sb.append("__HR_BEG__\r\nok\r\n__HR_END__0\r\n");
        byte[] b7 = sb.toString().getBytes("ISO-8859-1");
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        StringBuilder text7 = new StringBuilder();
        TelnetRoot.consume(b7, b7.length, reply, text7);
        TelnetRoot.Result r7 = TelnetRoot.extract(text7.toString());
        check("iac: output clean", r7 != null && r7.output.equals("ok"));
        check("iac: no 0xFF leaked", text7.indexOf("ÿ") < 0);
        byte[] resp = reply.toByteArray();
        check("iac: refused WILL with DONT",
                resp.length >= 6 && (resp[0] & 0xFF) == 255 && (resp[1] & 0xFF) == 254 && (resp[2] & 0xFF) == 1);
        check("iac: refused DO with WONT",
                resp.length >= 6 && (resp[3] & 0xFF) == 255 && (resp[4] & 0xFF) == 252 && (resp[5] & 0xFF) == 3);

        // 8. Entrega fragmentada
        StringBuilder text8 = new StringBuilder();
        ByteArrayOutputStream reply8 = new ByteArrayOutputStream();
        byte[] part1 = "__HR_BEG__\r\nchunked ".getBytes("ISO-8859-1");
        byte[] part2 = "value\r\n__HR_END__0\r\n".getBytes("ISO-8859-1");
        TelnetRoot.consume(part1, part1.length, reply8, text8);
        check("chunk1 incomplete", TelnetRoot.extract(text8.toString()) == null);
        TelnetRoot.consume(part2, part2.length, reply8, text8);
        TelnetRoot.Result r8 = TelnetRoot.extract(text8.toString());
        check("chunk2 complete", r8 != null && r8.output.equals("chunked value"));

        // 9. Linha ecoada não confundida com sentinela
        String tricky = "echo __HR_BEG__; ( true ); echo __HR_END__$?\r\n"
                + "__HR_BEG__\r\nreal\r\n__HR_END__0\r\n";
        TelnetRoot.Result r9 = run(tricky);
        check("echoed-input not confused", r9 != null && r9.output.equals("real"));

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}