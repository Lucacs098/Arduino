package it.bancofiches.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Server HTTP del banco. Serve la pagina web (gli altri giocatori non devono
 * installare nulla: basta il browser) e le API JSON.
 *
 * Gli aggiornamenti arrivano ai browser con il "long polling": GET /api/state
 * resta in attesa finché lo stato non cambia (max ~25 s).
 */
public final class FicheServer extends NanoHTTPD {

    /** Sorgente dei file statici (asset Android o cartella sul PC). */
    public interface Assets {
        InputStream open(String path) throws IOException;
    }

    private static final long POLL_TIMEOUT_MS = 25_000;
    private static final String JSON = "application/json; charset=utf-8";

    private final Game game;
    private final Assets assets;
    private final String adminToken;
    private final int port;
    private volatile List<String> urlsCache;
    private volatile long urlsTime;
    private volatile boolean running;

    public FicheServer(int port, Game game, Assets assets, String adminToken) {
        super(port);
        this.port = port;
        this.game = game;
        this.assets = assets;
        this.adminToken = adminToken;
    }

    public int getPort() {
        return port;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public Game getGame() {
        return game;
    }

    /** Avvia il server e il controllo periodico di chi è connesso. */
    public void startServer() throws IOException {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        running = true;
        Thread sweeper = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    game.sweepPresence();
                }
            }
        }, "presenza");
        sweeper.setDaemon(true);
        sweeper.start();
    }

    public void stopServer() {
        running = false;
        stop();
    }

    public List<String> urls() {
        long now = System.currentTimeMillis();
        if (urlsCache == null || now - urlsTime > 5000) {
            urlsCache = NetUtil.lanUrls(port);
            urlsTime = now;
        }
        return urlsCache;
    }

    @Override
    protected boolean useGzipWhenAccepted(Response r) {
        String m = r.getMimeType();
        return m != null && (m.startsWith("text/") || m.contains("json") || m.contains("javascript")
                || m.contains("svg"));
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        try {
            if (uri.startsWith("/api/")) {
                return api(session, uri.substring(5));
            }
            return file(uri);
        } catch (GameException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            return error(Response.Status.INTERNAL_ERROR, "Errore del server: " + e);
        }
    }

    private Response error(Response.Status status, String msg) {
        return json(status, new Json().obj().put("error", msg).end().toString());
    }

    private static Response json(Response.Status status, String body) {
        Response r = newFixedLengthResponse(status, JSON, body);
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    // ------------------------------------------------------------------

    private Response api(IHTTPSession s, String path) throws Exception {
        Map<String, String> prm = new HashMap<String, String>();
        if (Method.POST.equals(s.getMethod())) {
            s.parseBody(new HashMap<String, String>());
        }
        for (Map.Entry<String, List<String>> e : s.getParameters().entrySet()) {
            if (!e.getValue().isEmpty()) {
                prm.put(e.getKey(), e.getValue().get(0));
            }
        }
        Player me = game.byToken(prm.get("t"));
        boolean admin = adminToken.equals(prm.get("a"));

        if ("state".equals(path)) {
            long since = parseLong(prm.get("since"));
            if (me != null) {
                game.touch(me);
            }
            game.awaitChange(since, POLL_TIMEOUT_MS);
            if (me != null) {
                game.touch(me);
            }
            return json(Response.Status.OK, state(me, admin));
        }
        if (!Method.POST.equals(s.getMethod())) {
            return error(Response.Status.METHOD_NOT_ALLOWED, "Usa POST");
        }

        // --- azioni dei giocatori ---
        if ("join".equals(path)) {
            Player p = game.join(prm.get("name"));
            return json(Response.Status.OK, new Json().obj()
                    .put("token", p.getToken())
                    .put("id", p.getId())
                    .key("state").raw(state(p, admin))
                    .end().toString());
        }
        if ("act".equals(path) || "sitout".equals(path) || "rename".equals(path) || "leave".equals(path)) {
            int pid = targetPlayer(prm, me, admin);
            boolean byHost = admin && (me == null || me.getId() != pid);
            if ("act".equals(path)) {
                game.act(pid, str(prm, "action"), intOr(prm.get("amount"), 0), byHost);
            } else if ("sitout".equals(path)) {
                game.setSittingOut(pid, "1".equals(prm.get("value")));
            } else if ("rename".equals(path)) {
                game.rename(pid, prm.get("name"));
            } else {
                game.removePlayer(pid, byHost);
                if (!byHost) {
                    me = null;
                }
            }
            return ok(me, admin);
        }

        // --- azioni riservate al banco ---
        if (!admin) {
            return error(Response.Status.FORBIDDEN, "Solo il banco può farlo");
        }
        if ("start".equals(path)) {
            game.startHand();
        } else if ("add".equals(path)) {
            // Giocatore senza telefono: il banco agirà per lui.
            game.join(prm.get("name"));
        } else if ("award".equals(path)) {
            game.award(parseWinners(str(prm, "w")));
        } else if ("cancel".equals(path)) {
            game.cancelHand();
        } else if ("undo".equals(path)) {
            game.undo();
        } else if ("rebuy".equals(path)) {
            game.rebuy(intParam(prm, "pid"), intParam(prm, "amount"));
        } else if ("cashout".equals(path)) {
            game.cashOut(intParam(prm, "pid"), intParam(prm, "amount"));
        } else if ("setstack".equals(path)) {
            game.setStack(intParam(prm, "pid"), intParam(prm, "amount"));
        } else if ("move".equals(path)) {
            game.move(intParam(prm, "pid"), intParam(prm, "dir"));
        } else if ("dealer".equals(path)) {
            game.setDealer(intParam(prm, "pid"));
        } else if ("settings".equals(path)) {
            prm.remove("t");
            prm.remove("a");
            game.updateSettings(prm);
        } else if ("reset".equals(path)) {
            game.reset("1".equals(prm.get("keep")));
        } else if ("link".equals(path)) {
            return json(Response.Status.OK, new Json().obj()
                    .put("token", game.tokenOf(intParam(prm, "pid"))).end().toString());
        } else {
            return error(Response.Status.NOT_FOUND, "API sconosciuta: " + path);
        }
        return ok(me, admin);
    }

    private String state(Player me, boolean admin) {
        return game.stateJson(me, admin, admin ? urls() : null);
    }

    private Response ok(Player me, boolean admin) {
        // Dopo un'uscita il token non è più valido: rileggiamo il giocatore.
        Player fresh = me == null ? null : game.byToken(me.getToken());
        return json(Response.Status.OK, new Json().obj().put("ok", true)
                .key("state").raw(state(fresh, admin)).end().toString());
    }

    private static int targetPlayer(Map<String, String> prm, Player me, boolean admin) {
        String pid = prm.get("pid");
        if (admin && pid != null && !pid.isEmpty()) {
            return intParam(prm, "pid");
        }
        if (me == null) {
            throw new GameException("Non sei seduto al tavolo: ricarica la pagina");
        }
        if (pid != null && !pid.isEmpty() && intParam(prm, "pid") != me.getId()) {
            throw new GameException("Puoi agire solo per te stesso");
        }
        return me.getId();
    }

    /** Formato: "1,3;2" = piatto 0 a giocatori 1 e 3, piatto 1 al giocatore 2. */
    static int[][] parseWinners(String w) {
        String[] pots = w.split(";", -1);
        int[][] out = new int[pots.length][];
        for (int i = 0; i < pots.length; i++) {
            String[] ids = pots[i].trim().isEmpty() ? new String[0] : pots[i].split(",");
            out[i] = new int[ids.length];
            for (int k = 0; k < ids.length; k++) {
                out[i][k] = parseIntStrict(ids[k].trim());
            }
        }
        return out;
    }

    private static String str(Map<String, String> prm, String k) {
        String v = prm.get(k);
        if (v == null) {
            throw new GameException("Parametro mancante: " + k);
        }
        return v;
    }

    private static int intParam(Map<String, String> prm, String k) {
        return parseIntStrict(str(prm, k));
    }

    private static int parseIntStrict(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new GameException("Numero non valido: " + v);
        }
    }

    private static int intOr(String v, int def) {
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        return parseIntStrict(v);
    }

    private static long parseLong(String v) {
        try {
            return v == null ? -1 : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------

    private Response file(String uri) throws IOException {
        String path = uri.equals("/") || uri.isEmpty() ? "index.html" : uri.substring(1);
        if (path.contains("..") || path.contains("\\") || path.startsWith("/")) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Vietato");
        }
        InputStream in;
        try {
            in = assets.open(path);
        } catch (IOException e) {
            in = null;
        }
        if (in == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Non trovato");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        try {
            while ((r = in.read(buf)) > 0) {
                bos.write(buf, 0, r);
            }
        } finally {
            in.close();
        }
        byte[] data = bos.toByteArray();
        Response resp = newFixedLengthResponse(Response.Status.OK, mime(path), new ByteArrayInputStream(data),
                data.length);
        resp.addHeader("Cache-Control", "no-cache");
        return resp;
    }

    private static String mime(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/manifest+json";
        if (p.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }
}
