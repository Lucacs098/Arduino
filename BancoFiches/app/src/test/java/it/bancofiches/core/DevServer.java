package it.bancofiches.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Avvia il banco sul PC per provare l'interfaccia nel browser senza telefono.
 *
 * Uso: java ... it.bancofiches.core.DevServer [porta] [cartella-web] [token-banco]
 * Poi apri http://localhost:8080/#a=TOKEN per la vista del banco.
 */
public final class DevServer {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        final File web = new File(args.length > 1 ? args[1] : "app/src/main/assets/web");
        String admin = args.length > 2 ? args[2] : "banco";
        Game game = new Game();
        FicheServer server = new FicheServer(port, game, new FicheServer.Assets() {
            @Override
            public InputStream open(String path) throws IOException {
                File f = new File(web, path);
                return f.isFile() ? new FileInputStream(f) : null;
            }
        }, admin);
        server.startServer();
        System.out.println("Banco: http://localhost:" + port + "/#a=" + admin);
        for (String u : server.urls()) {
            System.out.println("Giocatori: " + u);
        }
        Thread.currentThread().join();
    }
}
