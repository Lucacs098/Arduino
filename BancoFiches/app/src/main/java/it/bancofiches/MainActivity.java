package it.bancofiches;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import it.bancofiches.core.FicheServer;

/**
 * Schermata del banco: avvia il server e mostra la stessa interfaccia web dei
 * giocatori, con in più i comandi del banco (token amministratore nell'URL).
 */
public class MainActivity extends Activity {
    private static final int FELT = 0xFF0B3D2A;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    /** URL del banco attualmente caricato (cambia se il server riparte con un nuovo token). */
    private String loadedUrl;
    private int waitTicks;

    private final Runnable waitForServer = new Runnable() {
        @Override
        public void run() {
            FicheServer s = ServerService.getServer();
            if (s != null) {
                String url = "http://127.0.0.1:" + s.getPort() + "/#a=" + s.getAdminToken();
                if (!url.equals(loadedUrl)) {
                    loadedUrl = url;
                    web.loadUrl(url);
                }
                return;
            }
            String err = ServerService.getStartError();
            if (err != null && waitTicks > 10) {
                showMessage(err);
                return;
            }
            waitTicks++;
            handler.postDelayed(this, 150);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(FELT);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("127.0.0.1".equals(u.getHost())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                    // nessun browser
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "BancoFiches");
        setContentView(web);
        showMessage(getString(R.string.starting));

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // (Ri)avvia il server se non è attivo, poi carica la pagina del banco.
        if (ServerService.getServer() == null) {
            startForegroundService(new Intent(this, ServerService.class));
        }
        waitTicks = 0;
        handler.removeCallbacks(waitForServer);
        handler.post(waitForServer);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(waitForServer);
        super.onPause();
    }

    private void showMessage(String text) {
        String html = "<html><body style=\"margin:0;height:100vh;display:grid;place-content:center;"
                + "background:#0b3d2a;color:#f2f7f4;font:18px system-ui;text-align:center;padding:24px\">"
                + text.replace("&", "&amp;").replace("<", "&lt;") + "</body></html>";
        loadedUrl = null;
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    @Override
    public void onBackPressed() {
        // Il banco resta attivo in background: si spegne dalle Opzioni o dalla notifica.
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(waitForServer);
        web.destroy();
        super.onDestroy();
    }

    /** Funzioni native richiamabili dalla pagina web (window.BancoFiches). */
    public final class Bridge {
        @JavascriptInterface
        public void share(final String url, final String table) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text, table, url));
                    startActivity(Intent.createChooser(send, getString(R.string.share_title)));
                }
            });
        }

        @JavascriptInterface
        public void stopServer() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    stopService(new Intent(MainActivity.this, ServerService.class));
                    finishAndRemoveTask();
                }
            });
        }
    }
}
