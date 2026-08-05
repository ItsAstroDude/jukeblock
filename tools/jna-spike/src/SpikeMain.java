import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phase 0 gate: prove the Rust SMTC cdylib loads and works from a plain JVM, with no
 * Minecraft in the picture. If this doesn't work, nothing downstream can.
 *
 * Run with the DLL path as arg[0].
 */
public class SpikeMain {

    public interface SmtcBridge extends Library {
        int jukeblock_abi_version();

        /**
         * Returns an owned C string. Deliberately typed as Pointer rather than String:
         * JNA would happily marshal a String for us, but then it discards the pointer
         * and we could never free it — a leak on every poll, several times a second.
         */
        Pointer jukeblock_get_now_playing();

        void jukeblock_free_string(Pointer p);

        Pointer jukeblock_get_thumbnail(LongByReference outLen);

        void jukeblock_free_bytes(Pointer p, long len);

        int jukeblock_control(String cmd, long arg);
    }

    private static SmtcBridge lib;

    /** Reads the JSON out and immediately hands the buffer back to Rust. */
    private static String nowPlaying() {
        Pointer p = lib.jukeblock_get_now_playing();
        if (p == null) return null;
        try {
            return p.getString(0, "UTF-8");
        } finally {
            lib.jukeblock_free_string(p);
        }
    }

    public static void main(String[] args) throws Exception {
        String dll = args.length > 0
                ? args[0]
                : "../../native/smtc-bridge/target/release/smtc_bridge.dll";
        File f = new File(dll).getCanonicalFile();
        System.out.println("DLL       : " + f);
        System.out.println("exists    : " + f.exists() + "  (" + f.length() + " bytes)");

        long t0 = System.nanoTime();
        lib = Native.load(f.getAbsolutePath(), SmtcBridge.class);
        System.out.printf("load time : %.1f ms%n", (System.nanoTime() - t0) / 1e6);

        System.out.println("abi ver   : " + lib.jukeblock_abi_version());
        System.out.println();

        // --- read path -------------------------------------------------------
        t0 = System.nanoTime();
        String json = nowPlaying();
        double firstCallMs = (System.nanoTime() - t0) / 1e6;
        System.out.printf("first call: %.1f ms%n", firstCallMs);
        System.out.println("now playing:");
        System.out.println("  " + json);
        System.out.println();

        // Polling cost matters: the panel wants ~500ms-1s polling, so this has to be cheap.
        int n = 20;
        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) nowPlaying();
        double avgMs = (System.nanoTime() - t0) / 1e6 / n;
        System.out.printf("avg of %d : %.2f ms per poll%n", n, avgMs);
        System.out.println();

        // --- thumbnail path --------------------------------------------------
        LongByReference len = new LongByReference();
        t0 = System.nanoTime();
        Pointer thumb = lib.jukeblock_get_thumbnail(len);
        double thumbMs = (System.nanoTime() - t0) / 1e6;
        if (thumb == null) {
            System.out.println("thumbnail : none");
        } else {
            try {
                int size = (int) len.getValue();
                byte[] bytes = thumb.getByteArray(0, size);
                String kind = sniff(bytes);
                System.out.printf("thumbnail : %d bytes, %s, fetched in %.1f ms%n",
                        size, kind, thumbMs);
                Path out = Path.of("thumbnail-probe." + kind.toLowerCase());
                Files.write(out, bytes);
                System.out.println("            written to " + out.toAbsolutePath());
            } finally {
                lib.jukeblock_free_bytes(thumb, len.getValue());
            }
        }
        System.out.println();

        // --- control path ----------------------------------------------------
        // Read-only probe: an unknown command exercises the whole call path (session
        // lookup included) without actually touching the user's playback.
        System.out.println("control(\"__probe__\") -> " + lib.jukeblock_control("__probe__", 0)
                + "   (0 = reached the session and declined, as expected)");

        System.out.println();
        System.out.println("GATE: PASSED");
    }

    private static String sniff(byte[] b) {
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "PNG";
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "JPG";
        return "BIN";
    }
}
