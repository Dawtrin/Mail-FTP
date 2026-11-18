package vn.edu.vku.mailserver.server.control;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Tìm địa chỉ IPv4 của máy để hiển thị cho client biết cần kết nối tới đâu. */
public final class NetworkAddresses {
    private static final String[] VIRTUAL_HINTS = {
            "virtualbox", "vmware", "vethernet", "hyper-v", "docker", "veth", "loopback", "bluetooth", "tap", "tun"
    };

    private NetworkAddresses() {}

    /** Địa chỉ LAN ưu tiên (site-local, không phải card ảo); không có thì 127.0.0.1. */
    public static String primary() {
        List<String> all = candidates();
        return all.isEmpty() ? "127.0.0.1" : all.get(0);
    }

    /** Mọi IPv4 không phải loopback đang hoạt động; card thật xếp trước card ảo, site-local xếp trước. */
    public static List<String> candidates() {
        List<String> real = new ArrayList<>();
        List<String> virtual = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String label = ((ni.getDisplayName() == null ? "" : ni.getDisplayName()) + " " + ni.getName())
                        .toLowerCase(Locale.ROOT);
                boolean isVirtual = false;
                for (String hint : VIRTUAL_HINTS) if (label.contains(hint)) { isVirtual = true; break; }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    (isVirtual ? virtual : real).add(a.getHostAddress());
                }
            }
        } catch (Exception e) {
            // không liệt kê được card mạng: rơi về 127.0.0.1 ở primary()
        }
        real.sort((x, y) -> Boolean.compare(!siteLocal(x), !siteLocal(y)));
        virtual.sort((x, y) -> Boolean.compare(!siteLocal(x), !siteLocal(y)));
        List<String> out = new ArrayList<>(real);
        out.addAll(virtual);
        return out;
    }

    private static boolean siteLocal(String ip) {
        try {
            return InetAddress.getByName(ip).isSiteLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
