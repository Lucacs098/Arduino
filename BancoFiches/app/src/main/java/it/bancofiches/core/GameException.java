package it.bancofiches.core;

/** Errore di gioco con un messaggio da mostrare all'utente. */
public final class GameException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GameException(String message) {
        super(message);
    }
}
