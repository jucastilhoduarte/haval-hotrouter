package com.castilhoduarte.jlh6;

import java.util.ArrayList;
import java.util.List;

/** Simulates the head-unit kernel network state and interprets the exact JLH6
 *  command vocabulary (ip rule / iptables / grep / ping / echo>file, with
 *  ; && || ! pipe and while/do/done). Not a general shell. */
public final class KernelShell implements Shell {

    private static final String IP_FWD = "/proc/sys/net/ipv4/ip_forward";

    private boolean ipForward = false;
    private final List<String> ipRules = new ArrayList<>();        // normalized: "iif wlan2 lookup wlan0"
    private final List<String> natPostrouting = new ArrayList<>(); // normalized iptables specs
    private final List<String> filterForward = new ArrayList<>();
    private final List<String> interfaces = new ArrayList<>();
    private boolean uplinkUp = false;
    private boolean failIpForwardWrite = false;

    public KernelShell() { interfaces.add("wlan0"); interfaces.add("wlan2"); }

    // ---- test controls ----
    public void setUplinkUp(boolean v) { uplinkUp = v; }
    public void setInterfacePresent(String iface, boolean present) {
        interfaces.remove(iface);
        if (present) interfaces.add(iface);
    }
    public void setFailIpForwardWrite(boolean v) { failIpForwardWrite = v; }

    // ---- inspectors (assertions) ----
    public boolean ipForward() { return ipForward; }
    public int ipRuleCount() { return ipRules.size(); }
    public int natCount() { return natPostrouting.size(); }
    public int forwardCount() { return filterForward.size(); }
    public boolean clean() { return ipRules.isEmpty() && natPostrouting.isEmpty() && filterForward.isEmpty(); }
    public boolean fullyApplied() {
        return ipForward && ipRules.size() == 1 && natPostrouting.size() == 1 && filterForward.size() == 2;
    }

    private static final class BreakSignal extends RuntimeException {}

    @Override public Shell.ShellResult exec(String command) {
        int last = 0;
        for (String stmt : splitStatements(command)) last = evalStatement(stmt);
        return new Shell.ShellResult("", last);
    }

    // split on top-level ';', re-grouping while...do...done into one statement
    private List<String> splitStatements(String cmd) {
        List<String> segs = new ArrayList<>();
        for (String s : cmd.split(";")) { String t = s.trim(); if (!t.isEmpty()) segs.add(t); }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) {
            String s = segs.get(i);
            if (s.startsWith("while ")) {
                StringBuilder block = new StringBuilder(s);
                while (i + 1 < segs.size() && !segs.get(i).equals("done")) {
                    i++;
                    block.append(" ; ").append(segs.get(i));
                }
                out.add(block.toString());
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private int evalStatement(String stmt) {
        stmt = stmt.trim();
        if (stmt.startsWith("while ")) return evalWhile(stmt);
        return evalAndOr(stmt);
    }

    private int evalWhile(String stmt) {
        int doIdx = stmt.indexOf(" ; do ");
        int doneIdx = stmt.lastIndexOf(" ; done");
        String cond = stmt.substring("while ".length(), doIdx).trim();
        String body = stmt.substring(doIdx + " ; do ".length(), doneIdx).trim();
        int guard = 0;
        while (evalAndOr(cond) == 0) {
            if (++guard > 10000) break;
            try { evalAndOr(body); } catch (BreakSignal b) { break; }
        }
        return 0;
    }

    private int evalAndOr(String expr) {
        List<String> parts = new ArrayList<>();
        List<String> ops = new ArrayList<>();
        splitTopLevel(expr, parts, ops);
        int status = evalPipeline(parts.get(0));
        for (int i = 0; i < ops.size(); i++) {
            String rhs = parts.get(i + 1);
            if (ops.get(i).equals("&&")) { if (status == 0) status = evalPipeline(rhs); }
            else                          { if (status != 0) status = evalPipeline(rhs); }
        }
        return status;
    }

    private void splitTopLevel(String expr, List<String> parts, List<String> ops) {
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '\'') { inQuote = !inQuote; cur.append(c); continue; }
            if (!inQuote && (c == '&' || c == '|') && i + 1 < expr.length() && expr.charAt(i + 1) == c) {
                parts.add(cur.toString().trim());
                ops.add("" + c + c);
                cur.setLength(0);
                i++;
                continue;
            }
            cur.append(c);
        }
        parts.add(cur.toString().trim());
    }

    private int evalPipeline(String p) {
        p = p.trim();
        if (p.startsWith("! ")) return evalPipeline(p.substring(2).trim()) == 0 ? 1 : 0;
        int pipe = indexOfTopLevel(p, '|');
        if (pipe >= 0) {
            String out = stdoutOf(p.substring(0, pipe).trim());
            return evalCommand(p.substring(pipe + 1).trim(), out);
        }
        return evalCommand(p, null);
    }

    private int indexOfTopLevel(String s, char ch) {
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') inQuote = !inQuote;
            else if (!inQuote && c == ch) return i;
        }
        return -1;
    }

    private String stdoutOf(String cmd) {
        cmd = stripRedir(cmd).trim();
        if (cmd.equals("ip rule")) {
            StringBuilder sb = new StringBuilder();
            for (String r : ipRules) sb.append("17999: from all ").append(r).append("\n");
            return sb.toString();
        }
        return "";
    }

    private int evalCommand(String cmd, String stdin) {
        cmd = stripRedir(cmd).trim();
        if (cmd.equals("break")) throw new BreakSignal();
        if (cmd.isEmpty()) return 0;
        String[] tok = cmd.split("\\s+");
        switch (tok[0]) {
            case "echo":     return doEcho(cmd);
            case "ip":       return doIpRule(cmd, tok);
            case "iptables": return doIptables(tok);
            case "grep":     return doGrep(cmd, stdin);
            case "ping":     return doPing(tok);
            default:         return 0;
        }
    }

    private String stripRedir(String cmd) { return cmd.replace("2>/dev/null", "").trim(); }

    private int doEcho(String cmd) {
        int gt = cmd.indexOf('>');
        if (gt < 0) return 0;
        String value = cmd.substring("echo".length(), gt).trim();
        String path = cmd.substring(gt + 1).trim();
        if (path.equals(IP_FWD)) {
            if (failIpForwardWrite) return 1;
            ipForward = value.equals("1");
        }
        return 0;
    }

    private int doIpRule(String cmd, String[] tok) {
        if (tok.length < 3) return 0;
        String op = tok[2];
        String key = ruleKey(cmd);
        if (op.equals("add")) { ipRules.add(key); return 0; }
        if (op.equals("del")) {
            for (int i = 0; i < ipRules.size(); i++) {
                if (ipRules.get(i).equals(key)) { ipRules.remove(i); return 0; }
            }
            return 2;
        }
        return 0;
    }

    private String ruleKey(String cmd) {
        int iif = cmd.indexOf("iif ");
        String hot = cmd.substring(iif + 4, cmd.indexOf(' ', iif + 4));
        int lk = cmd.indexOf("lookup ") + "lookup ".length();
        String table = cmd.substring(lk).trim().split("\\s+")[0];
        return "iif " + hot + " lookup " + table;
    }

    private int doIptables(String[] tok) {
        List<String> a = new ArrayList<>();
        for (String s : tok) a.add(s);
        boolean nat = false;
        int idx = 1;
        if (idx + 1 < a.size() && a.get(idx).equals("-t")) { nat = a.get(idx + 1).equals("nat"); idx += 2; }
        String op = a.get(idx); idx++;
        idx++; // chain
        if (op.equals("-I") && idx < a.size() && a.get(idx).matches("\\d+")) idx++; // insert position
        StringBuilder spec = new StringBuilder();
        for (int i = idx; i < a.size(); i++) { if (spec.length() > 0) spec.append(' '); spec.append(a.get(i)); }
        List<String> chain = nat ? natPostrouting : filterForward;
        String key = spec.toString();
        if (op.equals("-C")) return chain.contains(key) ? 0 : 1;
        if (op.equals("-I")) { chain.add(key); return 0; }
        if (op.equals("-D")) return chain.remove(key) ? 0 : 1;
        return 0;
    }

    private int doGrep(String cmd, String stdin) {
        if (cmd.contains("-qx")) {
            String after = cmd.substring(cmd.indexOf("-qx") + 3).trim();
            String[] p = after.split("\\s+");
            String pattern = p[0];
            String content = readFile(p.length > 1 ? p[1] : "");
            for (String line : content.split("\n", -1)) if (line.equals(pattern)) return 0;
            return 1;
        }
        String pattern = extractQuoted(cmd);
        String content = stdin == null ? "" : stdin;
        for (String line : content.split("\n", -1)) if (line.contains(pattern)) return 0;
        return 1;
    }

    private String extractQuoted(String cmd) {
        int a = cmd.indexOf('\''), b = cmd.lastIndexOf('\'');
        if (a >= 0 && b > a) return cmd.substring(a + 1, b);
        String[] t = cmd.trim().split("\\s+");
        return t[t.length - 1];
    }

    private String readFile(String path) { return path.equals(IP_FWD) ? (ipForward ? "1" : "0") : ""; }

    private int doPing(String[] tok) {
        String iface = null;
        for (int i = 0; i < tok.length - 1; i++) if (tok[i].equals("-I")) iface = tok[i + 1];
        boolean up = uplinkUp && (iface == null || interfaces.contains(iface));
        return up ? 0 : 1;
    }
}
