package app.mtx.toolbox.core;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pure-Java APK inspector. Reads the real manifest through {@link JavaAxml}, walks
 * the archive for DEX files, native libraries and META-INF, and probes the APK
 * Signing Block directly in the file. Nothing is guessed: whatever cannot be read
 * becomes a warning in the output rather than a fabricated value.
 */
final class JavaApk {

    private JavaApk() {}

    private static final int MAX_MANIFEST = 32 * 1024 * 1024;

    static String info(String path) {
        JavaEngine.clearError();
        StringBuilder out = new StringBuilder();
        File file = new File(path);
        if (!file.isFile()) {
            JavaEngine.setError("not found: " + path);
            return null;
        }

        List<String> dexFiles = new ArrayList<>();
        List<String> nativeLibs = new ArrayList<>();
        List<String> abis = new ArrayList<>();
        List<String> metaInf = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean hasArsc = false;
        boolean hasAssets = false;
        boolean hasV1 = false;
        boolean manifestPresent = false;
        long entryCount = 0;

        ZipFile zip = null;
        byte[] manifest = null;
        try {
            zip = new ZipFile(file);
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                entryCount++;
                String name = e.getName();
                if ("AndroidManifest.xml".equals(name)) {
                    manifestPresent = true;
                } else if ("resources.arsc".equals(name)) {
                    hasArsc = true;
                } else if (name.startsWith("assets/")) {
                    hasAssets = true;
                } else if (name.endsWith(".dex") && name.indexOf('/') < 0) {
                    dexFiles.add(name);
                } else if (name.startsWith("lib/") && name.endsWith(".so")) {
                    nativeLibs.add(name);
                    int slash = name.indexOf('/', 4);
                    if (slash > 4) {
                        String abi = name.substring(4, slash);
                        if (!abis.contains(abi)) abis.add(abi);
                    }
                } else if (name.startsWith("META-INF/")) {
                    metaInf.add(name);
                    String lower = name.toLowerCase();
                    if (lower.endsWith(".rsa") || lower.endsWith(".dsa") || lower.endsWith(".ec"))
                        hasV1 = true;
                }
            }
            Collections.sort(dexFiles);

            if (manifestPresent) manifest = JavaZip.read(path, "AndroidManifest.xml", MAX_MANIFEST);
        } catch (Throwable t) {
            JavaEngine.setError("cannot open APK: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        } finally {
            JavaEngine.closeQuietly(zip);
        }

        Manifest data = new Manifest();
        boolean ok = true;
        String error = "";

        if (!manifestPresent) {
            ok = false;
            error = "AndroidManifest.xml missing: not a valid APK";
            warnings.add("no AndroidManifest.xml: this ZIP is not an APK");
        } else if (manifest == null) {
            ok = false;
            error = "AndroidManifest.xml could not be read: " + JavaEngine.lastError();
            warnings.add(error);
        } else {
            try {
                JavaAxml.parse(manifest, data);
            } catch (Throwable t) {
                ok = false;
                error = "manifest decode failed: " + t.getMessage();
                warnings.add(error);
            }
        }

        Signing signing = probeSigningBlock(file);

        if (dexFiles.isEmpty()) warnings.add("no classes.dex found (resource-only or split APK)");
        if (!signing.hasBlock && !hasV1) warnings.add("no signature found: this APK is unsigned");

        kvBool(out, "ok", ok);
        if (!ok) kv(out, "error", error);
        kv(out, "path", path);
        kv(out, "fileSize", String.valueOf(file.length()));
        kv(out, "entryCount", String.valueOf(entryCount));
        kv(out, "package", data.packageName);
        kv(out, "versionName", data.versionName);
        kv(out, "versionCode", String.valueOf(data.versionCode));
        kv(out, "minSdk", String.valueOf(data.minSdk));
        kv(out, "targetSdk", String.valueOf(data.targetSdk));
        kv(out, "compileSdk", String.valueOf(data.compileSdk));
        kv(out, "label", data.label);
        kv(out, "icon", data.icon);
        kv(out, "mainActivity", data.mainActivity());
        kv(out, "split", data.splitName);
        kv(out, "installLocation", data.installLocation);
        kvBool(out, "debuggable", data.debuggable);
        kvBool(out, "extractNativeLibs", data.extractNativeLibs);
        kvBool(out, "cleartextTraffic", data.cleartextTraffic);
        kvBool(out, "hasArsc", hasArsc);
        kvBool(out, "hasAssets", hasAssets);
        kv(out, "dexCount", String.valueOf(dexFiles.size()));
        kvBool(out, "signingBlock", signing.hasBlock);
        kvBool(out, "schemeV2", signing.v2);
        kvBool(out, "schemeV3", signing.v3);
        kvBool(out, "schemeV31", signing.v31);
        kvBool(out, "schemeV1Files", hasV1);

        for (int i = 0; i < abis.size(); i++) kv(out, "abi", abis.get(i));
        for (int i = 0; i < dexFiles.size(); i++) kv(out, "dex", dexFiles.get(i));
        for (int i = 0; i < nativeLibs.size(); i++) kv(out, "lib", nativeLibs.get(i));
        for (int i = 0; i < data.permissions.size(); i++) kv(out, "permission", data.permissions.get(i));
        for (int i = 0; i < data.declaredPermissions.size(); i++)
            kv(out, "definesPermission", data.declaredPermissions.get(i));
        for (int i = 0; i < data.features.size(); i++) kv(out, "feature", data.features.get(i));
        for (int i = 0; i < data.libraries.size(); i++) kv(out, "usesLibrary", data.libraries.get(i));
        for (int i = 0; i < metaInf.size(); i++) kv(out, "metaInf", metaInf.get(i));

        for (int i = 0; i < data.components.size(); i++) {
            Component c = data.components.get(i);
            StringBuilder row = new StringBuilder();
            row.append(c.kind).append(JavaEngine.SEP)
                    .append(c.name).append(JavaEngine.SEP)
                    .append(c.exported ? '1' : '0').append(JavaEngine.SEP)
                    .append(c.enabled ? '1' : '0').append(JavaEngine.SEP);
            StringBuilder filters = new StringBuilder();
            for (int k = 0; k < c.actions.size(); k++) {
                if (filters.length() > 0) filters.append(" | ");
                filters.append(c.actions.get(k));
            }
            for (int k = 0; k < c.categories.size(); k++) {
                if (filters.length() > 0) filters.append(" | ");
                filters.append(c.categories.get(k));
            }
            row.append(filters);
            kv(out, "component", row.toString());
        }
        for (int i = 0; i < warnings.size(); i++) kv(out, "warning", warnings.get(i));
        return out.toString();
    }

    static String manifestXml(String apkPath) {
        JavaEngine.clearError();
        byte[] manifest = JavaZip.read(apkPath, "AndroidManifest.xml", MAX_MANIFEST);
        if (manifest == null) return null;
        try {
            return JavaAxml.toXml(manifest);
        } catch (Throwable t) {
            JavaEngine.setError("manifest decode failed: " + t.getMessage());
            return null;
        }
    }

    static String axmlFileToXml(String path) {
        JavaEngine.clearError();
        byte[] data = JavaEngine.hexRead(path, 0, MAX_MANIFEST);
        if (data == null) return null;
        try {
            return JavaAxml.toXml(data);
        } catch (Throwable t) {
            JavaEngine.setError("binary XML decode failed: " + t.getMessage());
            return null;
        }
    }

    // ---- manifest model ---------------------------------------------------
    private static final class Component {
        String kind = "";
        String name = "";
        boolean exported;
        boolean enabled = true;
        final List<String> actions = new ArrayList<>();
        final List<String> categories = new ArrayList<>();
    }

    private static final class Manifest implements JavaAxml.Handler {
        String packageName = "";
        String versionName = "";
        long versionCode = -1;
        int minSdk = -1;
        int targetSdk = -1;
        int compileSdk = -1;
        String label = "";
        String icon = "";
        String splitName = "";
        String installLocation = "";
        boolean debuggable;
        boolean extractNativeLibs = true;
        boolean cleartextTraffic;

        final List<String> permissions = new ArrayList<>();
        final List<String> declaredPermissions = new ArrayList<>();
        final List<String> features = new ArrayList<>();
        final List<String> libraries = new ArrayList<>();
        final List<Component> components = new ArrayList<>();

        private final List<String> stack = new ArrayList<>();
        private Component current;

        @Override
        public void startTag(String name, List<JavaAxml.Attr> attrs, int line) {
            stack.add(name);

            if ("manifest".equals(name)) {
                packageName = attr(attrs, "package", packageName);
                versionName = attr(attrs, "versionName", versionName);
                splitName = attr(attrs, "split", splitName);
                installLocation = attr(attrs, "installLocation", installLocation);
                versionCode = attrInt(attrs, "versionCode", -1);
                compileSdk = (int) attrInt(attrs, "compileSdkVersion", -1);
            } else if ("uses-sdk".equals(name)) {
                minSdk = (int) attrInt(attrs, "minSdkVersion", minSdk);
                targetSdk = (int) attrInt(attrs, "targetSdkVersion", targetSdk);
            } else if ("uses-permission".equals(name) || "uses-permission-sdk-23".equals(name)) {
                addIfPresent(attrs, permissions);
            } else if ("permission".equals(name)) {
                addIfPresent(attrs, declaredPermissions);
            } else if ("uses-feature".equals(name)) {
                addIfPresent(attrs, features);
            } else if ("uses-library".equals(name)) {
                addIfPresent(attrs, libraries);
            } else if ("application".equals(name)) {
                label = attr(attrs, "label", label);
                icon = attr(attrs, "icon", icon);
                debuggable = attrBool(attrs, "debuggable", false);
                extractNativeLibs = attrBool(attrs, "extractNativeLibs", true);
                cleartextTraffic = attrBool(attrs, "usesCleartextTraffic", false);
            } else if (isComponent(name)) {
                Component c = new Component();
                c.kind = "activity-alias".equals(name) ? "activity" : name;
                c.name = qualify(packageName, attr(attrs, "name", ""));
                c.exported = attrBool(attrs, "exported", false);
                c.enabled = attrBool(attrs, "enabled", true);
                components.add(c);
                current = c;
            } else if ("action".equals(name) && current != null && inIntentFilter()) {
                String value = attr(attrs, "name", "");
                if (!value.isEmpty()) current.actions.add(value);
            } else if ("category".equals(name) && current != null && inIntentFilter()) {
                String value = attr(attrs, "name", "");
                if (!value.isEmpty()) current.categories.add(value);
            }
        }

        @Override
        public void endTag(String name) {
            if (!stack.isEmpty()) stack.remove(stack.size() - 1);
            if (isComponent(name)) current = null;
        }

        @Override
        public void text(String value) { }

        String mainActivity() {
            for (int i = 0; i < components.size(); i++) {
                Component c = components.get(i);
                if (!"activity".equals(c.kind)) continue;
                if (c.actions.contains("android.intent.action.MAIN")
                        && c.categories.contains("android.intent.category.LAUNCHER"))
                    return c.name;
            }
            return "";
        }

        private boolean inIntentFilter() {
            for (int i = 0; i < stack.size(); i++) {
                if ("intent-filter".equals(stack.get(i))) return true;
            }
            return false;
        }

        private static boolean isComponent(String name) {
            return "activity".equals(name) || "activity-alias".equals(name) || "service".equals(name)
                    || "receiver".equals(name) || "provider".equals(name);
        }

        private void addIfPresent(List<JavaAxml.Attr> attrs, List<String> target) {
            String value = attr(attrs, "name", "");
            if (!value.isEmpty()) target.add(value);
        }

        private static String attr(List<JavaAxml.Attr> attrs, String name, String fallback) {
            for (int i = 0; i < attrs.size(); i++) {
                JavaAxml.Attr a = attrs.get(i);
                if (!name.equals(a.name)) continue;
                if (a.ns.isEmpty() || JavaAxml.ANDROID_NS.equals(a.ns)) return a.value;
            }
            return fallback;
        }

        private static boolean attrBool(List<JavaAxml.Attr> attrs, String name, boolean fallback) {
            String v = attr(attrs, name, null);
            if (v == null || v.isEmpty()) return fallback;
            return "true".equals(v) || "1".equals(v);
        }

        private static long attrInt(List<JavaAxml.Attr> attrs, String name, long fallback) {
            String v = attr(attrs, name, null);
            if (v == null || v.isEmpty()) return fallback;
            try {
                if (v.startsWith("0x")) return Long.parseLong(v.substring(2), 16);
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        /** Expands ".MainActivity" into "<package>.MainActivity", the way Android does. */
        private static String qualify(String pkg, String name) {
            if (name.isEmpty()) return name;
            if (name.charAt(0) == '.') return pkg + name;
            if (name.indexOf('.') < 0) return pkg + "." + name;
            return name;
        }
    }

    // ---- signing block ----------------------------------------------------
    private static final class Signing {
        boolean hasBlock;
        boolean v2;
        boolean v3;
        boolean v31;
    }

    /**
     * The APK Signing Block sits between the last entry and the central directory.
     * This reports only what is verifiably present; it is not a signature check.
     */
    private static Signing probeSigningBlock(File file) {
        Signing result = new Signing();
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            long size = raf.length();
            int tail = (int) Math.min(size, 66560);
            byte[] buf = new byte[tail];
            raf.seek(size - tail);
            raf.readFully(buf);

            int eocd = -1;
            for (int i = tail - 22; i >= 0; i--) {
                if (le32(buf, i) == 0x06054b50) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) return result;

            long cdOffset = le32(buf, eocd + 16) & 0xFFFFFFFFL;
            if (cdOffset < 24 || cdOffset > size) return result;

            byte[] footer = new byte[24];
            raf.seek(cdOffset - 24);
            raf.readFully(footer);
            String magic = new String(footer, 8, 16, "US-ASCII");
            if (!"APK Sig Block 42".equals(magic)) return result;
            result.hasBlock = true;

            long blockSize = le64(footer, 0);
            long blockStart = cdOffset - blockSize - 8;
            if (blockStart < 0 || blockSize > 64L * 1024 * 1024) return result;

            byte[] block = new byte[(int) (blockSize + 8)];
            raf.seek(blockStart);
            raf.readFully(block);

            int pos = 8;
            int limit = block.length - 24;
            while (pos + 12 <= limit) {
                long pairLen = le64(block, pos);
                int id = le32(block, pos + 8);
                if (pairLen < 4 || pos + 8 + pairLen > block.length) break;
                if (id == 0x7109871a) result.v2 = true;
                else if (id == 0xf05368c0) result.v3 = true;
                else if (id == 0x1b93ad61) result.v31 = true;
                pos += (int) (8 + pairLen);
            }
        } catch (Throwable t) {
            // A malformed tail is simply reported as "no block".
        } finally {
            JavaEngine.closeQuietly(raf);
        }
        return result;
    }

    static int le32(byte[] d, int off) {
        if (off < 0 || off + 4 > d.length) return 0;
        return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8)
                | ((d[off + 2] & 0xFF) << 16) | ((d[off + 3] & 0xFF) << 24);
    }

    static long le64(byte[] d, int off) {
        long lo = le32(d, off) & 0xFFFFFFFFL;
        long hi = le32(d, off + 4) & 0xFFFFFFFFL;
        return lo | (hi << 32);
    }

    private static void kv(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(value == null ? "" : value).append('\n');
    }

    private static void kvBool(StringBuilder out, String key, boolean value) {
        kv(out, key, value ? "true" : "false");
    }
}
