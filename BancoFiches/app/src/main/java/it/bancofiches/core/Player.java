package it.bancofiches.core;

import java.io.Serializable;

/** Un giocatore seduto al tavolo (o uscito, se si trova nella lista "departed"). */
public final class Player implements Serializable {
    private static final long serialVersionUID = 1L;

    final int id;
    final String token;
    String name;

    /** Fiches davanti al giocatore (non ancora puntate). */
    int stack;
    /** Totale fiches ricevute dal banco (stack iniziale + ricariche). */
    int buyIn;
    /** Totale fiches restituite al banco (ritiri, uscita dal tavolo). */
    int cashOut;
    /** Ricariche fatte durante una mano: entrano nello stack a fine mano. */
    int pendingChips;
    boolean sittingOut;

    // --- stato della mano in corso ---
    boolean inHand;
    boolean folded;
    boolean allIn;
    /** Ha già agito in questo giro di puntate (e nessuno ha rilanciato dopo). */
    boolean acted;
    /** Puntata nel giro corrente. */
    int bet;
    /** Totale messo nel piatto in questa mano (giro corrente compreso). */
    int contributed;
    String lastAction = "";

    // --- presenza (non salvata, non annullabile) ---
    transient long lastSeen;
    transient boolean online;

    Player(int id, String token, String name) {
        this.id = id;
        this.token = token;
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public String getName() {
        return name;
    }

    public int getStack() {
        return stack;
    }

    void resetHand() {
        inHand = false;
        folded = false;
        allIn = false;
        acted = false;
        bet = 0;
        contributed = 0;
        lastAction = "";
    }

    /** Sposta fiches dallo stack al piatto (puntata del giro corrente). */
    int putIn(int amount) {
        int pay = Math.min(amount, stack);
        stack -= pay;
        bet += pay;
        contributed += pay;
        if (stack == 0 && inHand) {
            allIn = true;
        }
        return pay;
    }
}
