package com.castilhoduarte.jlh6;

public class KernelShellTest {
    static int passed = 0, failed = 0;
    static void check(String n, boolean c) {
        if (c) { passed++; System.out.println("  ok   " + n); }
        else { failed++; System.out.println("  FAIL " + n); }
    }

    static final String ADD = "ip rule add from all iif wlan2 lookup wlan0 priority 17999";
    static final String DEDUP_WHILE =
        "while ip rule | grep -q 'iif wlan2 lookup wlan0'; do ip rule del from all iif wlan2 lookup wlan0 priority 17999 2>/dev/null || break; done";
    static final String NAT_IDEMP =
        "iptables -t nat -C POSTROUTING -o wlan0 -j MASQUERADE 2>/dev/null || iptables -t nat -I POSTROUTING 1 -o wlan0 -j MASQUERADE";

    // copies of the production strings (Task 5 #16 asserts RouterCore matches these shapes)
    static final String APPLY =
        "echo 1 > /proc/sys/net/ipv4/ip_forward; "
        + "while ip rule | grep -q 'iif wlan2 lookup wlan0'; do ip rule del from all iif wlan2 lookup wlan0 priority 17999 2>/dev/null || break; done; "
        + "ip rule add from all iif wlan2 lookup wlan0 priority 17999; "
        + "iptables -t nat -C POSTROUTING -o wlan0 -j MASQUERADE 2>/dev/null || iptables -t nat -I POSTROUTING 1 -o wlan0 -j MASQUERADE; "
        + "iptables -C FORWARD -i wlan2 -o wlan0 -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i wlan2 -o wlan0 -j ACCEPT; "
        + "iptables -C FORWARD -i wlan0 -o wlan2 -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i wlan0 -o wlan2 -m state --state RELATED,ESTABLISHED -j ACCEPT; "
        + "grep -qx 1 /proc/sys/net/ipv4/ip_forward 2>/dev/null "
        + "&& ip rule | grep -q 'iif wlan2 lookup wlan0' "
        + "&& iptables -t nat -C POSTROUTING -o wlan0 -j MASQUERADE 2>/dev/null "
        + "&& iptables -C FORWARD -i wlan2 -o wlan0 -j ACCEPT 2>/dev/null "
        + "&& iptables -C FORWARD -i wlan0 -o wlan2 -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null";
    static final String PURGE =
        "while ip rule | grep -q 'iif wlan2 lookup wlan0'; do ip rule del from all iif wlan2 lookup wlan0 priority 17999 2>/dev/null || break; done; "
        + "while iptables -t nat -C POSTROUTING -o wlan0 -j MASQUERADE 2>/dev/null; do iptables -t nat -D POSTROUTING -o wlan0 -j MASQUERADE 2>/dev/null || break; done; "
        + "while iptables -C FORWARD -i wlan2 -o wlan0 -j ACCEPT 2>/dev/null; do iptables -D FORWARD -i wlan2 -o wlan0 -j ACCEPT 2>/dev/null || break; done; "
        + "while iptables -C FORWARD -i wlan0 -o wlan2 -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null; do iptables -D FORWARD -i wlan0 -o wlan2 -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || break; done; "
        + "! ip rule | grep -q 'iif wlan2 lookup wlan0' "
        + "&& ! iptables -t nat -C POSTROUTING -o wlan0 -j MASQUERADE 2>/dev/null "
        + "&& ! iptables -C FORWARD -i wlan2 -o wlan0 -j ACCEPT 2>/dev/null "
        + "&& ! iptables -C FORWARD -i wlan0 -o wlan2 -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null";

    public static void main(String[] a) {
        KernelShell k = new KernelShell();
        check("iprule: empty grep fails", k.exec("ip rule | grep -q 'iif wlan2 lookup wlan0'").exitCode != 0);
        k.exec(ADD);
        check("iprule: add then grep ok", k.exec("ip rule | grep -q 'iif wlan2 lookup wlan0'").ok());
        check("iprule: count 1", k.ipRuleCount() == 1);
        k.exec(ADD);
        check("iprule: dup -> 2", k.ipRuleCount() == 2);
        k.exec(DEDUP_WHILE);
        check("iprule: while removed all", k.ipRuleCount() == 0);

        KernelShell k2 = new KernelShell();
        check("ipt: -C absent !=0", k2.exec("iptables -t nat -C POSTROUTING -o wlan0 -j MASQUERADE 2>/dev/null").exitCode != 0);
        k2.exec(NAT_IDEMP);
        check("ipt: inserted", k2.natCount() == 1);
        k2.exec(NAT_IDEMP);
        check("ipt: idempotent no dup", k2.natCount() == 1);
        k2.exec("iptables -t nat -D POSTROUTING -o wlan0 -j MASQUERADE 2>/dev/null");
        check("ipt: -D removes", k2.natCount() == 0);

        KernelShell k3 = new KernelShell();
        check("apply: exit 0", k3.exec(APPLY).exitCode == 0);
        check("apply: fullyApplied", k3.fullyApplied());
        check("apply: idempotent re-run still 1/1/2", k3.exec(APPLY).ok()
                && k3.ipRuleCount() == 1 && k3.natCount() == 1 && k3.forwardCount() == 2);
        check("purge: exit 0", k3.exec(PURGE).exitCode == 0);
        check("purge: clean", k3.clean());
        check("purge: idempotent re-run still clean", k3.exec(PURGE).ok() && k3.clean());

        KernelShell k4 = new KernelShell();
        check("ping: down fails", k4.exec("ping -I wlan0 -c 1 -W 2 8.8.8.8").exitCode != 0);
        k4.setUplinkUp(true);
        check("ping: up ok", k4.exec("ping -I wlan0 -c 1 -W 2 8.8.8.8").ok());
        k4.setInterfacePresent("wlan0", false);
        check("ping: iface gone fails", k4.exec("ping -I wlan0 -c 1 -W 2 8.8.8.8").exitCode != 0);

        KernelShell k5 = new KernelShell();
        k5.exec("echo 1 > /proc/sys/net/ipv4/ip_forward");
        check("ipfwd: set true", k5.ipForward());
        check("ipfwd: grep -qx ok", k5.exec("grep -qx 1 /proc/sys/net/ipv4/ip_forward 2>/dev/null").ok());

        KernelShell k6 = new KernelShell();
        k6.setFailIpForwardWrite(true);
        check("apply-fault: verify fails", k6.exec(APPLY).exitCode != 0);
        check("apply-fault: ipForward stuck 0", !k6.ipForward());
        check("apply-fault: rules still added", k6.ipRuleCount() == 1 && k6.natCount() == 1 && k6.forwardCount() == 2);

        KernelShell k7 = new KernelShell();
        k7.setFailIpForwardWrite(true);
        Shell.ShellResult echoResult = k7.exec("echo 1 > /proc/sys/net/ipv4/ip_forward");
        check("echo-fault: non-zero exit", echoResult.exitCode != 0);
        check("echo-fault: ipForward stays false", !k7.ipForward());

        KernelShell k8 = new KernelShell();
        Shell.ShellResult delResult = k8.exec("ip rule del from all iif wlan2 lookup wlan0 priority 17999");
        check("iprule-del: absent rule non-zero", delResult.exitCode != 0);

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
