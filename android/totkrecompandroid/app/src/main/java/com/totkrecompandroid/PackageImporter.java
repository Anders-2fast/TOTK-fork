package com.totkrecompandroid;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Game packages (NSP / XCI) as the game files: the player's own dump is opened here, the program NCA of the game (and of
// its update, patched over it) is decrypted by hactool (libhactool.so, run from nativeLibraryDir) into exefs/ and
// romfs.bin - the very files the folder import makes, so the native side goes on unchanged (nativeImportDone(0)).
//
// Needs the player's prod.keys (and title.keys, optional: an NSP's own common tickets give its title keys). Nothing is
// downloaded and no key is shipped. The game and its update (1.4.3) are picked together.
//
// Disk: the game's program NCA is copied out of the package for hactool (it reads files, not descriptors) while
// romfs.bin is written beside it - about 2x the game's size at the peak, freed at the end.
final class PackageImporter {
    private static final String TAG = "TOTKRecompAndroid";

    // What the activity gives this class: the native import steps (TOTKRecompAndroidActivity's natives)
    interface Host {
        void looking();
        int versionOf(byte[] head);       // nativeGameVersion
        File begin(String name, int version);  // nativeImportBegin: the version's folder, emptied
        void progress(long done, long total);
        void detail(String text);         // a failure's cause (nativeImportDetail)
        void done(int result);            // nativeImportDone: 0 ready, 4 another version, 5 failed, 1 cancelled
    }

    static final class Entry {
        String name;
        long off, size;  // absolute, in the package file
    }

    private static final class Pkg {
        Uri uri;
        String label;
        boolean xci;
        final List<Entry> ncas = new ArrayList<>();
        final List<Entry> tiks = new ArrayList<>();
        Entry program;       // the NCA tried first: the biggest
        long titleId;        // from a ticket's rights id, 0 without one
    }

    private final Context ctx;
    private final Host host;
    private final File home;  // hactool's HOME: .switch/prod.keys and title.keys

    PackageImporter(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
        this.home = new File(ctx.getFilesDir(), "pkg-home");
    }

    static File keysDir(Context c) {
        return new File(c.getFilesDir(), "keys");
    }

    static boolean hasProdKeys(Context c) {
        return new File(keysDir(c), "prod.keys").length() > 0;
    }

    // A keys file the player picked: prod.keys or title.keys by what's in it. The name saved, or null (not a keys file)
    static String saveKeys(Context c, Uri u) {
        try (java.io.InputStream in = c.getContentResolver().openInputStream(u)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int r; (r = in.read(buf)) > 0; ) {
                b.write(buf, 0, r);
                if (b.size() > (4 << 20)) return null;
            }
            String text = new String(b.toByteArray(), StandardCharsets.UTF_8);
            String name = null;
            if (text.contains("master_key_") || text.contains("header_key") || text.contains("aes_kek_generation_source")) name = "prod.keys";
            else if (text.matches("(?s)(\\s*[0-9a-fA-F]{32}\\s*=\\s*[0-9a-fA-F]{32}\\s*)+")) name = "title.keys";
            if (name == null) return null;
            File dir = keysDir(c);
            dir.mkdirs();
            try (FileOutputStream o = new FileOutputStream(new File(dir, name))) {
                o.write(b.toByteArray());
            }
            return name;
        } catch (Exception e) {
            Log.e(TAG, "keys: " + e);
            return null;
        }
    }

    // ---- the import (the activity's import thread)

    void run(List<Uri> uris) {
        File tmp = new File(ctx.getFilesDir(), "pkg-tmp");
        host.looking();
        try {
            if (!hasProdKeys(ctx)) {
                fail("Import your prod.keys first (Game files > Import prod.keys).");
                return;
            }
            deleteTree(tmp);
            tmp.mkdirs();
            List<Pkg> pkgs = new ArrayList<>();
            for (Uri u : uris) pkgs.add(open(u));
            Pkg base, update = null;
            if (pkgs.size() == 1) base = pkgs.get(0);
            else if (pkgs.size() == 2) {  // the update's title id ends in 800; else the bigger one is the game
                Pkg a = pkgs.get(0), b = pkgs.get(1);
                boolean aUpd = a.titleId != 0 && (a.titleId & 0xFFF) == 0x800, bUpd = b.titleId != 0 && (b.titleId & 0xFFF) == 0x800;
                if (aUpd == bUpd) {
                    boolean aBase = a.xci || a.program.size >= b.program.size;
                    base = aBase ? a : b;
                    update = aBase ? b : a;
                } else {
                    base = aUpd ? b : a;
                    update = aUpd ? a : b;
                }
                if (base.xci == false && update.xci) {  // (an XCI is never the update)
                    Pkg t = base;
                    base = update;
                    update = t;
                }
            } else {
                fail("Pick the game (NSP or XCI) and its update (NSP): two files at most.");
                return;
            }

            long need = base.program.size * 2 + (update != null ? update.program.size : 0) + (1L << 30);
            long free = ctx.getFilesDir().getUsableSpace();
            if (free < need) {
                fail(String.format(Locale.US, "Not enough free storage: about %.0f GB needed during the import (%.0f GB free).", need / 1e9, free / 1e9));
                return;
            }

            // keys: the player's prod.keys, and the title keys - their title.keys plus the packages' own tickets
            File sw = new File(home, ".switch");
            sw.mkdirs();
            copyFile(new File(keysDir(ctx), "prod.keys"), new File(sw, "prod.keys"));
            StringBuilder titleKeys = new StringBuilder();
            File user = new File(keysDir(ctx), "title.keys");
            if (user.length() > 0) titleKeys.append(readText(user)).append('\n');
            for (Pkg p : pkgs) titleKeys.append(ticketKeys(p));
            try (FileOutputStream o = new FileOutputStream(new File(sw, "title.keys"))) {
                o.write(titleKeys.toString().getBytes(StandardCharsets.UTF_8));
            }

            final long copied = base.program.size + (update != null ? update.program.size : 0);  // (the NCAs copied out)
            final long total = copied + base.program.size;  // (and the image about as big as the game's NCA)
            long done = 0;
            host.progress(0, total);
            File baseNca = new File(tmp, "base.nca");
            done = extract(base, base.program, baseNca, done, total);
            File progNca = baseNca, baseArg = null;
            if (update != null) {
                File updNca = new File(tmp, "update.nca");
                done = extract(update, update.program, updNca, done, total);
                progNca = updNca;
                baseArg = baseNca;
            }

            // first the exefs alone: the game's version decides which folder the files go to
            File exefsTmp = new File(tmp, "exefs");
            exefsTmp.mkdirs();
            String out = hactool(progNca, baseArg, exefsTmp, null, null);
            File main = new File(exefsTmp, "main");
            if (!main.isFile()) {
                fail("The game's code couldn't be decrypted. Check the prod.keys" + (update != null ? "" : " - and add the 1.4.3 update (NSP) with the game") +
                        ".\n" + tail(out));
                return;
            }
            byte[] head = new byte[0x48];
            try (FileInputStream in = new FileInputStream(main)) {
                int got = 0;
                for (int r; got < head.length && (r = in.read(head, got, head.length - got)) > 0; ) got += r;
            }
            int version = host.versionOf(head);
            if (version == 0) {
                host.detail("These files aren't the version this app runs (1.4.3). Pick the game together with its 1.4.3 update (NSP).");
                host.done(4);
                return;
            }

            String name = base.label;
            File games = host.begin(name, version);  // (emptied of an earlier setup: after the check above)
            File exefs = new File(games, "exefs");
            exefs.mkdirs();
            File[] parts = exefsTmp.listFiles();
            if (parts != null) for (File f : parts) moveFile(f, new File(exefs, f.getName()));

            // then the game's data image, written where the game reads it
            File romfsBin = new File(games, "romfs.bin");
            File scratch = new File(tmp, "exefs2");
            scratch.mkdirs();
            out = hactool(progNca, baseArg, scratch, romfsBin, () -> host.progress(Math.min(total - 1, copied + romfsBin.length()), total));
            String bad = romfsProblem(romfsBin);
            if (bad != null) {
                romfsBin.delete();
                fail("The game data (romfs) couldn't be decrypted: " + bad + ".\n" + tail(out));
                return;
            }
            host.progress(total - 1, total);
            host.detail("");
            extractLoose(romfsBin, new File(games, "romfs"));  // (the movies, opened by path: runtime/ui/launcher.mm keep_loose)
            host.progress(total, total);
            deleteTree(tmp);
            host.done(0);
        } catch (Exception e) {
            Log.e(TAG, "package import: " + e, e);
            fail(e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        } finally {
            deleteTree(tmp);
            deleteTree(home);  // (the keys' copy: the player's own stay in keys/)
        }
    }

    private void fail(String why) {
        host.detail(why);
        host.done(5);
    }

    // ---- hactool

    // hactool on a program NCA (with the game's NCA under it when it's the update's): exefs into exefsDir, the romfs image
    // to romfs when given. Its output (the tail of it, for a failure's cause). tick: called about once a second meanwhile
    private String hactool(File nca, File base, File exefsDir, File romfs, Runnable tick) throws IOException, InterruptedException {
        File exe = new File(ctx.getApplicationInfo().nativeLibraryDir, "libhactool.so");
        if (!exe.canExecute()) throw new IOException("libhactool.so is missing from this build");
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.getPath());
        cmd.add("-k");
        cmd.add(new File(new File(home, ".switch"), "prod.keys").getPath());
        cmd.add("--disablekeywarns");
        cmd.add("--suppresskeys");  // (no keys in the output: it goes to the log)
        cmd.add("-t");
        cmd.add("nca");
        cmd.add("--xcontenttype=program");
        if (base != null) cmd.add("--basenca=" + base.getPath());
        cmd.add("--exefsdir=" + exefsDir.getPath());
        if (romfs != null) cmd.add("--romfs=" + romfs.getPath());
        cmd.add(nca.getPath());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("HOME", home.getPath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        final StringBuilder log = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    synchronized (log) {
                        log.append(line).append('\n');
                        if (log.length() > 20000) log.delete(0, log.length() - 10000);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "hactool-out");
        reader.start();
        while (true) {
            try {
                p.exitValue();
                break;
            } catch (IllegalThreadStateException running) {
                if (tick != null) tick.run();
                Thread.sleep(1000);
            }
        }
        reader.join(2000);
        String text;
        synchronized (log) {
            text = log.toString();
        }
        Log.i(TAG, "hactool exit " + p.exitValue() + ": " + tail(text));
        return text;
    }

    private static String tail(String s) {
        String[] lines = s.trim().split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, lines.length - 4); i < lines.length; i++) {
            if (lines[i].toLowerCase(Locale.ROOT).contains("key") && lines[i].matches(".*[0-9a-fA-F]{32}.*")) continue;  // (never a key)
            b.append(lines[i]).append('\n');
        }
        return b.toString().trim();
    }

    // ---- the package files (PFS0 for an NSP, HFS0 for an XCI's partitions)

    private Pkg open(Uri u) throws IOException {
        Pkg p = new Pkg();
        p.uri = u;
        p.label = displayName(u);
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(u, "r");
             FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            FileChannel ch = in.getChannel();
            byte[] magic = read(ch, 0x100, 4);
            List<Entry> files;
            if (new String(magic, StandardCharsets.ISO_8859_1).equals("HEAD")) {  // an XCI: the root HFS0's "secure" partition
                p.xci = true;
                long root = le64(read(ch, 0x130, 8), 0);
                files = new ArrayList<>();
                for (Entry part : fs(ch, root)) if (part.name.equals("secure")) files.addAll(fs(ch, part.off));
                if (files.isEmpty()) throw new IOException("This XCI has no secure partition (is it a trimmed or partial dump?)");
            } else {
                files = fs(ch, 0);
            }
            for (Entry e : files) {
                String n = e.name.toLowerCase(Locale.ROOT);
                if (n.endsWith(".nca") && !n.endsWith(".cnmt.nca")) p.ncas.add(e);
                else if (n.endsWith(".tik")) p.tiks.add(e);
            }
            if (p.ncas.isEmpty()) throw new IOException(p.label + " holds no NCA files");
            Entry big = p.ncas.get(0);
            for (Entry e : p.ncas) if (e.size > big.size) big = e;
            p.program = big;
            for (Entry t : p.tiks) {  // (the title id: the first 16 hex digits of the rights id, which is the ticket's name)
                String n = t.name;
                if (n.length() >= 16) {
                    try {
                        p.titleId = Long.parseUnsignedLong(n.substring(0, 16), 16);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return p;
    }

    // The files of a PFS0 / HFS0 at abs (offsets made absolute)
    private static List<Entry> fs(FileChannel ch, long abs) throws IOException {
        byte[] h = read(ch, abs, 16);
        String magic = new String(h, 0, 4, StandardCharsets.ISO_8859_1);
        boolean hfs = magic.equals("HFS0");
        if (!hfs && !magic.equals("PFS0")) throw new IOException("Not an NSP or XCI (no PFS0/HFS0 header)");
        int n = le32(h, 4), strSize = le32(h, 8), each = hfs ? 0x40 : 0x18;
        if (n < 0 || n > 4096 || strSize < 0 || strSize > (1 << 20)) throw new IOException("The package's header is damaged");
        byte[] table = read(ch, abs + 16, n * each);
        byte[] strings = read(ch, abs + 16 + (long) n * each, strSize);
        long data = abs + 16 + (long) n * each + strSize;
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Entry e = new Entry();
            long off = le64(table, i * each), size = le64(table, i * each + 8);
            int no = le32(table, i * each + 16);
            int end = no;
            while (end < strings.length && strings[end] != 0) end++;
            e.name = new String(strings, no, end - no, StandardCharsets.UTF_8);
            e.off = data + off;
            e.size = size;
            out.add(e);
        }
        return out;
    }

    // A title key per common ticket, as title.keys lines (rights id = encrypted title key)
    private String ticketKeys(Pkg p) throws IOException {
        StringBuilder b = new StringBuilder();
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(p.uri, "r");
             FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            FileChannel ch = in.getChannel();
            for (Entry t : p.tiks) {
                if (t.size < 0x200 || t.size > 0x10000) continue;
                byte[] tik = read(ch, t.off, (int) t.size);
                int sig = le32(tik, 0), sigLen;
                switch (sig) {
                    case 0x10000: case 0x10003: sigLen = 0x200; break;
                    case 0x10001: case 0x10004: sigLen = 0x100; break;
                    case 0x10002: case 0x10005: sigLen = 0x3C; break;
                    default: continue;
                }
                int body = 4 + sigLen + (sigLen == 0x3C ? 0x40 : 0x3C);
                if (tik.length < body + 0x170) continue;
                if (tik[body + 0x146] != 0) continue;  // (a personal ticket: its key is encrypted for one console - use a title.keys)
                b.append(hex(tik, body + 0x160, 16)).append(" = ").append(hex(tik, body + 0x40, 16)).append('\n');
            }
        }
        return b.toString();
    }

    // The NCA out of the package into a file (hactool reads files). done + its bytes back
    private long extract(Pkg p, Entry e, File to, long done, long total) throws IOException {
        try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(p.uri, "r");
             FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(to)) {
            FileChannel src = in.getChannel(), dst = out.getChannel();
            long pos = e.off, left = e.size, lastReport = done;
            while (left > 0) {
                long n = src.transferTo(pos, Math.min(left, 16 << 20), dst);
                if (n <= 0) throw new IOException(p.label + " ends early (the file is cut short)");
                pos += n;
                left -= n;
                done += n;
                if (done - lastReport > (16 << 20)) {
                    host.progress(done, total);
                    lastReport = done;
                }
            }
        }
        return done;
    }

    private String displayName(Uri u) {
        String name = null;
        try (android.database.Cursor c = ctx.getContentResolver().query(u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        }
        if (name == null) name = u.getLastPathSegment() == null ? "game" : u.getLastPathSegment();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ---- the image hactool wrote

    // null when romfs.bin is whole (a header the runtime reads, and every table inside the file), else what's wrong
    static String romfsProblem(File f) throws IOException {
        if (!f.isFile()) return "no image was written";
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            if (r.length() < 0x50) return "the image is empty";
            byte[] h = new byte[0x50];
            r.readFully(h);
            if (le64(h, 0) != 0x50) return "the image's header is damaged";
            long end = le64(h, 0x48);
            for (int i = 1; i < 9; i += 2) {
                long off = le64(h, i * 8), size = le64(h, i * 8 + 8);
                if (off > (1L << 40) || size > (1L << 40)) return "the image's header is damaged";
                end = Math.max(end, off + size);
            }
            if (r.length() < end) return String.format(Locale.US, "the image has %.2f of its %.2f GB (is the storage full?)", r.length() / 1e9, end / 1e9);
        }
        return null;
    }

    // The loose few of the image (Movie/ and the .webm files): written under dir with their paths
    static void extractLoose(File img, File dir) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(img, "r")) {
            byte[] h = new byte[0x50];
            r.readFully(h);
            long dirMetaOff = le64(h, 0x18), dirMetaSize = le64(h, 0x20), fileMetaOff = le64(h, 0x38), fileMetaSize = le64(h, 0x40), dataOff = le64(h, 0x48);
            if (dirMetaSize > (256 << 20) || fileMetaSize > (256 << 20)) throw new IOException("the image's tables are too big");
            byte[] dm = new byte[(int) dirMetaSize], fm = new byte[(int) fileMetaSize];
            r.seek(dirMetaOff);
            r.readFully(dm);
            r.seek(fileMetaOff);
            r.readFully(fm);
            Map<Integer, String> names = new HashMap<>();
            Map<Integer, Integer> parents = new HashMap<>();
            for (int p = 0; p + 0x18 <= dm.length; ) {
                int len = le32(dm, p + 0x14);
                if (len < 0 || p + 0x18 + len > dm.length) break;
                parents.put(p, le32(dm, p));
                names.put(p, new String(dm, p + 0x18, len, StandardCharsets.UTF_8));
                p += 0x18 + ((len + 3) & ~3);
            }
            Map<Integer, String> paths = new HashMap<>();
            for (int p = 0; p + 0x20 <= fm.length; ) {
                int len = le32(fm, p + 0x1C);
                if (len < 0 || p + 0x20 + len > fm.length) break;
                int parent = le32(fm, p);
                long off = le64(fm, p + 8), size = le64(fm, p + 0x10);
                String name = new String(fm, p + 0x20, len, StandardCharsets.UTF_8);
                p += 0x20 + ((len + 3) & ~3);
                String rel = dirPath(parent, names, parents, paths) + name;
                if (!(rel.startsWith("Movie/") || rel.endsWith(".webm"))) continue;
                File to = new File(dir, rel);
                to.getParentFile().mkdirs();
                r.seek(dataOff + off);
                try (FileOutputStream o = new FileOutputStream(to)) {
                    byte[] buf = new byte[1 << 20];
                    for (long left = size; left > 0; ) {
                        int n = r.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n <= 0) throw new IOException("the image ends inside " + rel);
                        o.write(buf, 0, n);
                        left -= n;
                    }
                }
            }
        }
    }

    private static String dirPath(int dir, Map<Integer, String> names, Map<Integer, Integer> parents, Map<Integer, String> memo) {
        if (dir == 0) return "";
        String known = memo.get(dir);
        if (known != null) return known;
        String name = names.get(dir);
        Integer parent = parents.get(dir);
        String path = name == null || parent == null || parent == dir ? "" : dirPath(parent, names, parents, memo) + name + "/";
        memo.put(dir, path);
        return path;
    }

    // ---- bytes

    private static byte[] read(FileChannel ch, long pos, int n) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(n);
        while (b.hasRemaining()) {
            int r = ch.read(b, pos + b.position());
            if (r <= 0) throw new IOException("The package ends early");
        }
        return b.array();
    }

    private static int le32(byte[] b, int o) {
        return ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static long le64(byte[] b, int o) {
        return ByteBuffer.wrap(b, o, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static String hex(byte[] b, int o, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append(String.format("%02x", b[o + i] & 255));
        return s.toString();
    }

    private static String readText(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void copyFile(File a, File b) throws IOException {
        try (FileInputStream in = new FileInputStream(a); FileOutputStream o = new FileOutputStream(b)) {
            byte[] buf = new byte[65536];
            for (int r; (r = in.read(buf)) > 0; ) o.write(buf, 0, r);
        }
    }

    private static void moveFile(File a, File b) throws IOException {
        if (a.renameTo(b)) return;
        copyFile(a, b);
        a.delete();
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
