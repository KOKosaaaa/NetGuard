package hev.sockstun;

/**
 * JNI bridge to hev-socks5-tunnel (libhev-socks5-tunnel.so).
 *
 * The native library registers its methods dynamically in JNI_OnLoad via
 * RegisterNatives, looking up EXACTLY the class "hev/sockstun/TProxyService"
 * and binding these names/signatures (pinned build: tools/build-hev.py):
 *   TProxyStartService (Ljava/lang/String;I)Z
 *   TProxyStopService  ()Z
 *   TProxyIsRunning   ()Z
 *   TProxyGetStats     ()[J
 * The package/class name and signatures
 *  must match or System.loadLibrary() -> JNI_OnLoad -> FindClass would fail.
 *
 * The shipped JNI wrapper starts native worker threads and returns. Stop them
 * with TProxyStopService() before closing the descriptor. The tun fd is used but NOT
 * closed by the native side — the caller (VpnService) keeps ownership and
 * closes the ParcelFileDescriptor after TProxyStopService() returns.
 *
 * Unlike badvpn-tun2socks, this carries UDP through a real SOCKS5 UDP-ASSOCIATE
 * with full-cone semantics (config: socks5.udp = 'udp'), which is what makes
 * Telegram/WebRTC voice+video calls work through the tunnel.
 */
public class TProxyService {
    public static native boolean TProxyStartService(String config_path, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }
}
