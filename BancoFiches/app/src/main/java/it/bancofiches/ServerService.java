package it.bancofiches;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.List;

import it.bancofiches.core.FicheServer;
import it.bancofiches.core.Game;

/**
 * Servizio in primo piano che tiene acceso il server del banco anche con lo
 * schermo spento o l'app in background.
 */
public class ServerService extends Service {
    public static final String ACTION_STOP = "it.bancofiches.action.STOP";
    private static final String CHANNEL = "banco";
    private static final int NOTIFICATION_ID = 1;
    private static final int FIRST_PORT = 8080;
    private static final int LAST_PORT = 8099;

    private static volatile FicheServer server;
    private static volatile String startError;
    private static boolean starting;
    /** Istanza attiva del servizio (null dopo onDestroy). */
    private static ServerService instance;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public static FicheServer getServer() {
        return server;
    }

    public static String getStartError() {
        return startError;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (ServerService.class) {
            instance = this;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (RuntimeException e) {
            // Avvio in primo piano negato dal sistema (es. riavvio dal background): rinunciamo.
            stopSelf();
            return START_NOT_STICKY;
        }
        acquireLocks();
        startServerAsync();
        return START_STICKY;
    }

    private void startServerAsync() {
        synchronized (ServerService.class) {
            if (server != null || starting) {
                return;
            }
            starting = true;
        }
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                Game game = new Game();
                File save = new File(app.getFilesDir(), "partita.bin");
                game.load(save);
                game.enableAutosave(save);
                final AssetManager assets = app.getAssets();
                FicheServer.Assets web = new FicheServer.Assets() {
                    @Override
                    public InputStream open(String path) throws IOException {
                        return assets.open("web/" + path);
                    }
                };
                String admin = randomToken();
                FicheServer started = null;
                String error = null;
                for (int port = FIRST_PORT; port <= LAST_PORT && started == null; port++) {
                    FicheServer s = new FicheServer(port, game, web, admin);
                    try {
                        s.startServer();
                        started = s;
                    } catch (IOException e) {
                        error = e.getMessage();
                    }
                }
                if (started == null) {
                    game.stopAutosave();
                    startError = "Impossibile avviare il server: " + error;
                } else {
                    startError = null;
                }
                ServerService alive;
                synchronized (ServerService.class) {
                    starting = false;
                    alive = instance;
                    if (alive != null) {
                        server = started;
                    }
                }
                if (alive == null) {
                    // Il servizio è stato fermato mentre il server partiva.
                    if (started != null) {
                        started.stopServer();
                        game.stopAutosave();
                    }
                    return;
                }
                alive.updateNotification();
            }
        }, "avvio-banco").start();
    }

    private static String randomToken() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    private void acquireLocks() {
        if (wakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BancoFiches:server");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        if (wifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BancoFiches:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, ServerService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String text;
        FicheServer s = server;
        if (s != null) {
            List<String> urls = s.urls();
            text = urls.isEmpty() ? getString(R.string.notification_no_network)
                    : getString(R.string.notification_address, urls.get(0));
        } else if (startError != null) {
            text = startError;
        } else {
            text = getString(R.string.notification_starting);
        }
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, getString(R.string.notification_stop), stopPi).build())
                .build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public void onDestroy() {
        FicheServer s;
        synchronized (ServerService.class) {
            s = server;
            server = null;
            instance = null;
        }
        if (s != null) {
            s.stopServer();
            s.getGame().stopAutosave();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        super.onDestroy();
    }
}
