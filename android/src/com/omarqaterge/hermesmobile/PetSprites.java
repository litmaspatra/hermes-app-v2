package com.omarqaterge.hermesmobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pets from AgentPets (agentpets.dev, MIT, the Petdex pet format): each is one spritesheet of 8 columns x 9 rows of
 * frames, one row per state (idle, run right, run left, waving, jumping, failed, waiting, running, review).
 * The catalogue and the sheets are downloaded on demand (nothing is bundled) and kept in the app's own folder.
 * The pet's setting is "ap-<slug>". Network calls block: use a background thread.
 */
final class PetSprites {
    private PetSprites() {}

    static final String PREFIX = "ap-";
    static final int COLS = 8, ROWS = 9;
    // row, frames, duration in ms, for: idle, running-right, running-left, waving, jumping, failed, waiting, running, review
    static final int[][] STATES = {{0, 6, 1100}, {1, 8, 1060}, {2, 8, 1060}, {3, 4, 700}, {4, 5, 840}, {5, 8, 1220}, {6, 6, 1010}, {7, 6, 820}, {8, 6, 1030}};
    static final int IDLE = 0, RIGHT = 1, LEFT = 2, WAVE = 3, JUMP = 4, FAIL = 5, WAIT = 6, RUN = 7, REVIEW = 8;

    private static final String MANIFEST = "https://agentpets.dev/api/manifest";
    private static final Map<String, Bitmap> cache = new HashMap<>();

    static boolean isSprite(String species) {
        return species != null && species.startsWith(PREFIX) && validSlug(species.substring(PREFIX.length()));
    }

    /** Hosts AgentPets itself trusts for pet files (its own allowlist): nothing else is ever downloaded. */
    private static final java.util.Set<String> HOSTS = new java.util.HashSet<>(java.util.Arrays.asList(
            "pub-94495283df974cfea5e98d6a9e3fa462.r2.dev", "yu2vz9gndp.ufs.sh"));

    static boolean trustedUrl(String url) {
        try {
            URL u = new URL(url);
            return "https".equals(u.getProtocol()) && HOSTS.contains(u.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    static boolean validSlug(String s) {
        return s != null && s.matches("[a-z0-9][a-z0-9-]{0,59}");
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "pets");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** The sheet of an installed pet, or null. */
    static synchronized Bitmap sheet(Context c, String species) {
        if (!isSprite(species)) return null;
        String slug = species.substring(PREFIX.length());
        Bitmap b = cache.get(slug);
        if (b != null) return b;
        File f = new File(dir(c), slug + ".img");
        if (!f.isFile()) return null;
        b = BitmapFactory.decodeFile(f.getPath());
        if (b != null) cache.put(slug, b);
        return b;
    }

    static List<String> installed(Context c) {
        List<String> out = new ArrayList<>();
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".img")) out.add(f.getName().substring(0, f.getName().length() - 4));
        java.util.Collections.sort(out);
        return out;
    }

    private static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36 HermesMobile";

    /** The pet host has refused some clients with 401, so try it three ways: a browser identity, none, then referer only. */
    private static byte[] get(String url, int max) throws Exception {
        Exception last = null;
        for (int mode = 0; mode < 3; mode++) {
            try {
                return getOnce(url, max, mode);
            } catch (Exception e) {
                last = e;
                if (e.getMessage() == null || !e.getMessage().startsWith("HTTP 40")) break; // only auth/refusals are worth another try
            }
        }
        throw last;
    }

    private static byte[] getOnce(String url, int max, int mode) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(20000);
        h.setInstanceFollowRedirects(true);
        if (mode == 0) {
            h.setRequestProperty("User-Agent", UA);
            h.setRequestProperty("Accept", "*/*");
            h.setRequestProperty("Referer", "https://agentpets.dev/");
        } else if (mode == 2) {
            h.setRequestProperty("Referer", "https://agentpets.dev/");
        }
        try {
            if (h.getResponseCode() != 200) throw new Exception("HTTP " + h.getResponseCode() + " from " + h.getURL().getHost());
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    if (o.size() > max) throw new Exception("too large");
                }
            }
            return o.toByteArray();
        } finally {
            h.disconnect();
        }
    }

    /** A pet sheet the person picked from their own files (downloaded in a browser): same checks, no network. */
    static String importSheet(Context c, String name, byte[] data) {
        String slug = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT).replaceAll("\\.[a-z0-9]+$", "").replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 60) slug = slug.substring(0, 60);
        if (!validSlug(slug)) slug = "my-pet";
        if (data == null || data.length > 10_000_000) return "file too large";
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (b == null || b.getWidth() < COLS * 8 || b.getHeight() < ROWS * 8) return "not a pet spritesheet (8 columns x 9 rows)";
        try (FileOutputStream o = new FileOutputStream(new File(dir(c), slug + ".img"))) {
            o.write(data);
        } catch (Exception e) {
            return "couldn't save";
        }
        synchronized (PetSprites.class) {
            cache.put(slug, b);
        }
        return "";
    }

    /** [{"slug","name","url"}] from AgentPets, `query` filters by name/slug; at most `limit` entries. */
    static String catalog(String query, int offset, int limit) {
        try {
            JSONObject m = new JSONObject(new String(get(MANIFEST, 12_000_000), "UTF-8"));
            JSONArray pets = m.optJSONArray("pets");
            JSONArray out = new JSONArray();
            String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
            int skipped = 0;
            for (int i = 0; pets != null && i < pets.length() && out.length() < limit; i++) {
                JSONObject p = pets.optJSONObject(i);
                if (p == null) continue;
                String slug = p.optString("slug"), name = p.optString("displayName", slug), url = p.optString("spritesheetUrl");
                if (!validSlug(slug) || !trustedUrl(url)) continue;
                if (!q.isEmpty() && !slug.contains(q) && !name.toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
                if (skipped++ < offset) continue;
                out.put(new JSONObject().put("slug", slug).put("name", name).put("url", url));
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Downloads one pet's sheet. "" = installed, otherwise what went wrong. The URL must be the catalogue's https one. */
    static String install(Context c, String slug, String url) {
        if (!validSlug(slug) || !trustedUrl(url)) return "not a trusted AgentPets file";
        try {
            byte[] data;
            try {
                data = get(url, 8_000_000);
            } catch (Exception first) { // the pet CDN rate-limits bursts: one patient retry
                Thread.sleep(1500);
                data = get(url, 8_000_000);
            }
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b == null || b.getWidth() < COLS * 8 || b.getHeight() < ROWS * 8) return "not a pet spritesheet";
            File f = new File(dir(c), slug + ".img");
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(data);
            }
            synchronized (PetSprites.class) {
                cache.put(slug, b);
            }
            return "";
        } catch (Exception e) {
            return e.getMessage() == null ? "download failed" : e.getMessage();
        }
    }
}
