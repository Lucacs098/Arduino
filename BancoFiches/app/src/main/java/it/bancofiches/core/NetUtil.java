package it.bancofiches.core;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

/** Indirizzi della rete locale a cui gli altri telefoni possono collegarsi. */
public final class NetUtil {
    private NetUtil() {
    }

    private static final String[] SKIP = {
            "rmnet", "ccmni", "v4-", "clat", "dummy", "tun", "ppp", "pdp", "docker", "veth", "br-", "virbr", "ipsec"
    };

    private static int priority(String name) {
        if (name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap")
                || name.startsWith("softap") || name.startsWith("wl")) {
            return 0;
        }
        if (name.startsWith("eth") || name.startsWith("en") || name.startsWith("rndis") || name.startsWith("usb")) {
            return 1;
        }
        return 2;
    }

    /** URL del tipo http://192.168.1.20:8080/ ordinati per probabilità di essere il Wi-Fi/hotspot. */
    public static List<String> lanUrls(int port) {
        final List<Object[]> found = new ArrayList<Object[]>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                boolean down = false;
                try {
                    down = !ni.isUp() || ni.isLoopback();
                } catch (Exception e) {
                    // Alcuni Android non permettono di leggere lo stato: decidono gli indirizzi.
                }
                if (down) {
                    continue;
                }
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                boolean skip = false;
                for (String s : SKIP) {
                    if (name.startsWith(s)) {
                        skip = true;
                        break;
                    }
                }
                if (skip) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        found.add(new Object[]{priority(name), a.getHostAddress()});
                    }
                }
            }
        } catch (Exception ignored) {
            // nessuna rete disponibile
        }
        Collections.sort(found, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return ((Integer) a[0]).compareTo((Integer) b[0]);
            }
        });
        if (found.isEmpty()) {
            String ip = routedAddress();
            if (ip != null) {
                found.add(new Object[]{3, ip});
            }
        }
        List<String> urls = new ArrayList<String>();
        for (Object[] f : found) {
            String u = "http://" + f[1] + ":" + port + "/";
            if (!urls.contains(u)) {
                urls.add(u);
            }
        }
        return urls;
    }

    /** Ripiego: indirizzo locale usato per uscire verso la rete (nessun pacchetto viene inviato). */
    private static String routedAddress() {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.connect(InetAddress.getByName("192.168.255.255"), 9);
            InetAddress a = s.getLocalAddress();
            if (a instanceof Inet4Address && !a.isAnyLocalAddress() && !a.isLoopbackAddress()) {
                return a.getHostAddress();
            }
        } catch (Exception ignored) {
            // nessuna rete
        } finally {
            if (s != null) {
                s.close();
            }
        }
        return null;
    }
}
