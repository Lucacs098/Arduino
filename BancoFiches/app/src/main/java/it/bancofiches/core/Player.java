package it.bancofiches.core;

import java.io.Serializable;
import java.util.ArrayList;

/** Un giocatore seduto al tavolo (o uscito, se si trova nella lista "departed"). */
public final class Player implements Serializable {
    private static final long serialVersionUID = 1L;

    // Esiti di una mano di blackjack.
    static final int BJ_NONE = 0;
    static final int BJ_LOSE = 1;
    static final int BJ_PUSH = 2;
    static final int BJ_WIN = 3;
    static final int BJ_BLACKJACK = 4;
    static final int BJ_SURRENDER = 5;

    /** Una mano di blackjack (dopo uno split il giocatore ne ha più di una). */
    static final class Hand implements Serializable {
        private static final long serialVersionUID = 1L;
        int bet;
        boolean doubled;
        int outcome = BJ_NONE;

        Hand(int bet) {
            this.bet = bet;
        }

        /** Fiches restituite al giocatore per questo esito (puntata compresa). */
        int payout() {
            switch (outcome) {
                case BJ_PUSH: return bet;
                case BJ_WIN: return 2 * bet;
                case BJ_BLACKJACK: return bet + bet * 3 / 2;
                case BJ_SURRENDER: return bet / 2;
                default: return 0;
            }
        }
    }

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

    // --- blackjack ---
    /** Mani della mano in corso (può essere null nelle partite salvate da versioni precedenti). */
    ArrayList<Hand> hands;
    /** Assicurazione pagata in questa mano. */
    int insurance;
    /** Puntata iniziale di questa mano (base per l'assicurazione). */
    int baseBet;
    /** Puntata della mano precedente, per il pulsante "Come prima". */
    int lastBet;

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
        hands = null;
        insurance = 0;
        baseBet = 0;
    }

    ArrayList<Hand> hands() {
        if (hands == null) {
            hands = new ArrayList<Hand>();
        }
        return hands;
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
