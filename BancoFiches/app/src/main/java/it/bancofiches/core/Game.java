package it.bancofiches.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Il banco: tiene le fiches di tutti e gestisce le puntate stile Texas Hold'em
 * (bui, giri di puntata, rilanci minimi, all-in, piatti laterali).
 * Le carte restano fisiche: l'app gestisce solo le fiches.
 *
 * Tutti i metodi pubblici sono sincronizzati: il server HTTP li chiama da più thread.
 */
public final class Game {
    public static final int STAGE_IDLE = 0;
    public static final int STAGE_BETTING = 1;
    public static final int STAGE_SHOWDOWN = 2;

    static final String[] STREETS = {"Pre-flop", "Flop", "Turn", "River"};
    static final int MAX_CHIPS = 100_000_000;
    static final long ONLINE_TIMEOUT_MS = 35_000;
    private static final int MAX_UNDO = 60;
    private static final int MAX_LOG = 150;

    /** Impostazioni del tavolo (non coinvolte dall'annulla). */
    public static final class Settings implements Serializable {
        private static final long serialVersionUID = 1L;
        String tableName = "Tavolo da poker";
        int startingStack = 1000;
        int smallBlind = 5;
        int bigBlind = 10;
        int ante = 0;
        boolean autoBlinds = true;
        int[] chips = {5, 25, 100, 500};
        /** Valore in denaro di una fiche (0 = non usato). */
        double chipValue = 0;
        String currency = "€";
        boolean locked = false;
    }

    /** Stato annullabile: giocatori e mano in corso. */
    static final class State implements Serializable {
        private static final long serialVersionUID = 1L;
        ArrayList<Player> players = new ArrayList<Player>();
        ArrayList<Player> departed = new ArrayList<Player>();
        int nextId = 1;
        int handNumber = 0;
        int stage = STAGE_IDLE;
        int street = 0;
        int dealerId = -1;
        boolean dealerFixed = false;
        int sbId = -1;
        int bbId = -1;
        int currentBet = 0;
        int minRaise = 0;
        int toActId = -1;
        ArrayList<Pot> pots = new ArrayList<Pot>();
        String lastResult = "";
    }

    static final class Pot implements Serializable {
        private static final long serialVersionUID = 1L;
        int amount;
        int[] eligible;

        Pot(int amount, int[] eligible) {
            this.amount = amount;
            this.eligible = eligible;
        }
    }

    static final class LogEntry implements Serializable {
        private static final long serialVersionUID = 1L;
        final int seq;
        final int hand;
        final String text;

        LogEntry(int seq, int hand, String text) {
            this.seq = seq;
            this.hand = hand;
            this.text = text;
        }
    }

    /** Contenitore per il salvataggio su file. */
    private static final class Saved implements Serializable {
        private static final long serialVersionUID = 1L;
        Settings cfg;
        State st;
        ArrayList<LogEntry> log;
        int logSeq;
    }

    private static final class Snapshot {
        final byte[] data;
        final String label;

        Snapshot(byte[] data, String label) {
            this.data = data;
            this.label = label;
        }
    }

    private Settings cfg = new Settings();
    private State st = new State();
    private ArrayList<LogEntry> log = new ArrayList<LogEntry>();
    private int logSeq = 0;
    private final ArrayDeque<Snapshot> undo = new ArrayDeque<Snapshot>();
    private final SecureRandom random = new SecureRandom();
    private final String instanceId = Long.toHexString(new SecureRandom().nextLong());
    private long version = 1;

    private File saveFile;
    private boolean dirty;
    private boolean saverRunning;

    // ------------------------------------------------------------------
    // Ricerca giocatori e ordine dei posti
    // ------------------------------------------------------------------

    private static final int M_ELIGIBLE = 0;
    private static final int M_IN_HAND = 1;
    private static final int M_ACTIVE = 2;
    private static final int M_CAN_ACT = 3;
    private static final int M_NEEDS_ACTION = 4;

    private boolean matches(Player p, int mode) {
        switch (mode) {
            case M_ELIGIBLE: return !p.sittingOut && p.stack > 0;
            case M_IN_HAND: return p.inHand;
            case M_ACTIVE: return p.inHand && !p.folded;
            case M_CAN_ACT: return p.inHand && !p.folded && !p.allIn;
            case M_NEEDS_ACTION: return needsAction(p);
            default: return false;
        }
    }

    private boolean needsAction(Player p) {
        return p.inHand && !p.folded && !p.allIn && (!p.acted || p.bet < st.currentBet);
    }

    private int indexOf(int id) {
        for (int i = 0; i < st.players.size(); i++) {
            if (st.players.get(i).id == id) {
                return i;
            }
        }
        return -1;
    }

    private Player byId(int id) {
        int i = indexOf(id);
        return i < 0 ? null : st.players.get(i);
    }

    private Player require(int id) {
        Player p = byId(id);
        if (p == null) {
            throw new GameException("Giocatore non trovato");
        }
        return p;
    }

    /** Primo giocatore dopo {@code id} in senso orario che soddisfa {@code mode} (lui stesso per ultimo). */
    private Player nextAfter(int id, int mode) {
        int n = st.players.size();
        int start = indexOf(id);
        for (int k = 1; k <= n; k++) {
            Player p = st.players.get(((start + k) % n + n) % n);
            if (matches(p, mode)) {
                return p;
            }
        }
        return null;
    }

    private int count(int mode) {
        int c = 0;
        for (Player p : st.players) {
            if (matches(p, mode)) {
                c++;
            }
        }
        return c;
    }

    private int minBet() {
        return Math.max(1, cfg.bigBlind);
    }

    private int potTotal() {
        int t = 0;
        for (Player p : st.players) {
            t += p.contributed;
        }
        return t;
    }

    private int betsOnTable() {
        int t = 0;
        for (Player p : st.players) {
            t += p.bet;
        }
        return t;
    }

    private boolean playerLocked(Player p) {
        return st.stage != STAGE_IDLE && p.inHand && !p.folded;
    }

    // ------------------------------------------------------------------
    // Giocatori
    // ------------------------------------------------------------------

    public synchronized Player byToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        for (Player p : st.players) {
            if (p.token.equals(token)) {
                return p;
            }
        }
        return null;
    }

    public synchronized String tokenOf(int playerId) {
        return require(playerId).token;
    }

    static String cleanName(String raw) {
        if (raw == null) {
            raw = "";
        }
        StringBuilder b = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                space = b.length() > 0;
                continue;
            }
            if (space) {
                b.append(' ');
                space = false;
            }
            b.append(c);
        }
        String s = b.toString();
        if (s.isEmpty()) {
            throw new GameException("Scrivi un nome");
        }
        if (s.codePointCount(0, s.length()) > 20) {
            throw new GameException("Nome troppo lungo (max 20 caratteri)");
        }
        return s;
    }

    private void checkNameFree(String name, int exceptId) {
        for (Player p : st.players) {
            if (p.id != exceptId && p.name.equalsIgnoreCase(name)) {
                throw new GameException("Il nome \"" + name + "\" è già usato al tavolo");
            }
        }
    }

    public synchronized Player join(String rawName) {
        String name = cleanName(rawName);
        if (cfg.locked) {
            throw new GameException("Il tavolo è chiuso a nuovi giocatori");
        }
        if (st.players.size() >= 23) {
            throw new GameException("Tavolo pieno");
        }
        checkNameFree(name, -1);
        byte[] t = new byte[16];
        random.nextBytes(t);
        StringBuilder tok = new StringBuilder();
        for (byte b : t) {
            tok.append(String.format("%02x", b & 0xff));
        }
        Player p = new Player(st.nextId++, tok.toString(), name);
        p.stack = cfg.startingStack;
        p.buyIn = cfg.startingStack;
        p.lastSeen = System.currentTimeMillis();
        p.online = true;
        st.players.add(p);
        log(name + " si siede al tavolo con " + fmt(p.stack));
        changed();
        return p;
    }

    public synchronized void rename(int pid, String rawName) {
        Player p = require(pid);
        String name = cleanName(rawName);
        checkNameFree(name, pid);
        if (!name.equals(p.name)) {
            log(p.name + " ora si chiama " + name);
            p.name = name;
            changed();
        }
    }

    public synchronized void removePlayer(int pid, boolean byHost) {
        Player p = require(pid);
        if (st.stage != STAGE_IDLE && p.inHand) {
            throw new GameException("Aspetta la fine della mano per far uscire " + p.name);
        }
        pushUndo((byHost ? "Rimozione di " : "Uscita di ") + p.name);
        int idx = indexOf(pid);
        if (st.dealerId == pid && st.players.size() > 1) {
            // Il bottone passa al posto successivo alla prossima mano.
            Player prev = st.players.get((idx - 1 + st.players.size()) % st.players.size());
            st.dealerId = prev.id;
            st.dealerFixed = false;
        }
        p.cashOut += p.stack + p.pendingChips;
        p.stack = 0;
        p.pendingChips = 0;
        p.resetHand();
        st.players.remove(idx);
        st.departed.add(p);
        log(p.name + (byHost ? " è stato tolto dal tavolo" : " lascia il tavolo")
                + " (riconsegna " + fmt(p.cashOut) + " in totale)");
        changed();
    }

    public synchronized void setSittingOut(int pid, boolean value) {
        Player p = require(pid);
        if (p.sittingOut != value) {
            p.sittingOut = value;
            log(p.name + (value ? " salta le prossime mani" : " torna in gioco"));
            changed();
        }
    }

    public synchronized void move(int pid, int dir) {
        if (st.stage != STAGE_IDLE) {
            throw new GameException("Puoi cambiare i posti solo tra una mano e l'altra");
        }
        int i = indexOf(pid);
        if (i < 0) {
            throw new GameException("Giocatore non trovato");
        }
        int j = i + (dir < 0 ? -1 : 1);
        if (j < 0 || j >= st.players.size()) {
            return;
        }
        Player a = st.players.get(i);
        st.players.set(i, st.players.get(j));
        st.players.set(j, a);
        changed();
    }

    public synchronized void setDealer(int pid) {
        if (st.stage != STAGE_IDLE) {
            throw new GameException("Puoi spostare il mazziere solo tra una mano e l'altra");
        }
        Player p = require(pid);
        st.dealerId = pid;
        st.dealerFixed = true;
        log("Il mazziere della prossima mano sarà " + p.name);
        changed();
    }

    // ------------------------------------------------------------------
    // Fiches del banco
    // ------------------------------------------------------------------

    private static int checkAmount(int amount) {
        if (amount <= 0 || amount > MAX_CHIPS) {
            throw new GameException("Importo non valido");
        }
        return amount;
    }

    public synchronized void rebuy(int pid, int amount) {
        Player p = require(pid);
        checkAmount(amount);
        pushUndo("Ricarica di " + p.name);
        p.buyIn += amount;
        if (playerLocked(p)) {
            p.pendingChips += amount;
            log(p.name + " ricarica " + fmt(amount) + " (disponibili dalla prossima mano)");
        } else {
            p.stack += amount;
            log(p.name + " ricarica " + fmt(amount));
        }
        changed();
    }

    public synchronized void cashOut(int pid, int amount) {
        Player p = require(pid);
        checkAmount(amount);
        if (playerLocked(p)) {
            throw new GameException(p.name + " è in mano: aspetta la fine della mano");
        }
        if (amount > p.stack) {
            throw new GameException(p.name + " ha solo " + fmt(p.stack) + " fiches");
        }
        pushUndo("Ritiro fiches di " + p.name);
        p.stack -= amount;
        p.cashOut += amount;
        log(p.name + " riconsegna " + fmt(amount) + " fiches al banco");
        changed();
    }

    public synchronized void setStack(int pid, int value) {
        Player p = require(pid);
        if (value < 0 || value > MAX_CHIPS) {
            throw new GameException("Importo non valido");
        }
        if (playerLocked(p)) {
            throw new GameException(p.name + " è in mano: aspetta la fine della mano");
        }
        pushUndo("Correzione stack di " + p.name);
        log("Stack di " + p.name + " corretto: " + fmt(p.stack) + " → " + fmt(value));
        p.stack = value;
        changed();
    }

    // ------------------------------------------------------------------
    // Mano
    // ------------------------------------------------------------------

    public synchronized void startHand() {
        if (st.stage != STAGE_IDLE) {
            throw new GameException("C'è già una mano in corso");
        }
        if (count(M_ELIGIBLE) < 2) {
            throw new GameException("Servono almeno 2 giocatori con fiches e non in pausa");
        }
        pushUndo("Nuova mano");
        st.handNumber++;
        st.street = 0;
        st.pots.clear();
        st.lastResult = "";
        for (Player p : st.players) {
            p.resetHand();
            p.inHand = matches(p, M_ELIGIBLE);
        }

        Player dealer = byId(st.dealerId);
        if (!(st.dealerFixed && dealer != null && dealer.inHand)) {
            dealer = nextAfter(st.dealerId, M_IN_HAND);
        }
        st.dealerFixed = false;
        st.dealerId = dealer.id;
        log("Mano #" + st.handNumber + " — mazziere " + dealer.name);

        if (cfg.ante > 0) {
            for (Player p : st.players) {
                if (p.inHand) {
                    p.contributed += Math.min(cfg.ante, p.stack);
                    p.stack -= Math.min(cfg.ante, p.stack);
                    if (p.stack == 0) {
                        p.allIn = true;
                    }
                }
            }
            log("Ante di " + fmt(cfg.ante) + " a testa");
        }

        st.currentBet = 0;
        st.minRaise = minBet();
        int firstFrom;
        if (cfg.autoBlinds && cfg.bigBlind > 0) {
            Player sb;
            Player bb;
            if (count(M_IN_HAND) == 2) {
                sb = dealer;
                bb = nextAfter(dealer.id, M_IN_HAND);
            } else {
                sb = nextAfter(dealer.id, M_IN_HAND);
                bb = nextAfter(sb.id, M_IN_HAND);
            }
            post(sb, cfg.smallBlind, "Small blind");
            post(bb, cfg.bigBlind, "Big blind");
            st.sbId = sb.id;
            st.bbId = bb.id;
            int max = cfg.bigBlind;
            for (Player p : st.players) {
                max = Math.max(max, p.bet);
            }
            st.currentBet = max;
            firstFrom = bb.id;
        } else {
            st.sbId = -1;
            st.bbId = -1;
            firstFrom = dealer.id;
        }
        st.stage = STAGE_BETTING;
        st.toActId = -1;
        if (roundComplete()) {
            finishStreet();
        } else {
            st.toActId = nextAfter(firstFrom, M_NEEDS_ACTION).id;
        }
        changed();
    }

    private void post(Player p, int amount, String label) {
        if (amount <= 0) {
            return;
        }
        int paid = p.putIn(amount);
        p.lastAction = label + " " + fmt(paid) + (p.allIn ? " (all-in)" : "");
        log(p.name + ": " + p.lastAction.toLowerCase(Locale.ITALIAN));
    }

    /** Non resta nessuno che debba ancora agire in questo giro. */
    private boolean roundComplete() {
        int canAct = 0;
        Player only = null;
        for (Player p : st.players) {
            if (matches(p, M_CAN_ACT)) {
                canAct++;
                only = p;
            }
        }
        if (canAct == 0) {
            return true;
        }
        if (canAct == 1) {
            // Gli altri sono all-in o hanno passato: se ha già coperto le loro puntate non c'è altro da fare.
            int maxOthers = 0;
            for (Player p : st.players) {
                if (p != only && p.inHand) {
                    maxOthers = Math.max(maxOthers, p.bet);
                }
            }
            if (only.bet >= maxOthers) {
                return true;
            }
        }
        for (Player p : st.players) {
            if (needsAction(p)) {
                return false;
            }
        }
        return true;
    }

    /** Può (ri)lanciare: non ha già agito dopo l'ultimo rilancio completo e qualcuno può rispondere. */
    private boolean canRaise(Player p) {
        if (p.acted || p.bet + p.stack <= st.currentBet) {
            return false;
        }
        for (Player q : st.players) {
            if (q != p && matches(q, M_CAN_ACT)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Azione del giocatore di turno.
     *
     * @param action fold, check, call, raise (amount = totale a cui portare la puntata), allin
     */
    public synchronized void act(int pid, String action, int amount, boolean byHost) {
        if (st.stage != STAGE_BETTING) {
            throw new GameException("Non ci sono puntate in corso");
        }
        Player p = require(pid);
        if (st.toActId != pid) {
            Player turn = byId(st.toActId);
            throw new GameException("Non è il tuo turno" + (turn != null ? ": tocca a " + turn.name : ""));
        }
        int toCall = Math.max(0, st.currentBet - p.bet);
        int maxTo = p.bet + p.stack;
        if ("allin".equals(action)) {
            if (maxTo > st.currentBet && canRaise(p)) {
                action = "raise";
                amount = maxTo;
            } else {
                action = "call";
            }
        }
        if ("call".equals(action) && toCall == 0) {
            action = "check";
        }

        String label;
        if ("fold".equals(action)) {
            pushUndo(p.name + ": fold");
            p.folded = true;
            label = "Fold";
        } else if ("check".equals(action)) {
            if (toCall > 0) {
                throw new GameException("Non puoi fare check: devi chiamare " + fmt(Math.min(toCall, p.stack)));
            }
            pushUndo(p.name + ": check");
            label = "Check";
        } else if ("call".equals(action)) {
            pushUndo(p.name + ": chiama");
            int paid = p.putIn(toCall);
            label = p.allIn ? "All-in " + fmt(p.bet) : "Chiama " + fmt(paid);
        } else if ("raise".equals(action)) {
            if (!canRaise(p)) {
                throw new GameException("Ora puoi solo chiamare o passare");
            }
            if (amount > maxTo) {
                throw new GameException("Non hai abbastanza fiches (massimo " + fmt(maxTo) + ")");
            }
            if (amount <= st.currentBet) {
                throw new GameException("Devi puntare più di " + fmt(st.currentBet));
            }
            int minTo = st.currentBet + st.minRaise;
            if (amount < minTo && amount != maxTo) {
                throw new GameException((st.currentBet == 0 ? "La puntata minima è " : "Il rilancio minimo è a ")
                        + fmt(minTo));
            }
            boolean opening = st.currentBet == 0;
            pushUndo(p.name + (opening ? ": punta " : ": rilancia a ") + fmt(amount));
            p.putIn(amount - p.bet);
            int increment = amount - st.currentBet;
            if (increment >= st.minRaise) {
                st.minRaise = increment;
                for (Player q : st.players) {
                    if (q != p && matches(q, M_CAN_ACT)) {
                        q.acted = false;
                    }
                }
            }
            st.currentBet = amount;
            label = p.allIn ? "All-in " + fmt(amount) : (opening ? "Punta " : "Rilancia a ") + fmt(amount);
        } else {
            throw new GameException("Azione sconosciuta");
        }
        p.acted = true;
        p.lastAction = label;
        log(p.name + ": " + label.toLowerCase(Locale.ITALIAN) + (byHost ? " (dal banco)" : ""));
        advance();
        changed();
    }

    private void advance() {
        if (count(M_ACTIVE) == 1) {
            returnUncalled();
            Player w = nextAfter(-1, M_ACTIVE);
            int total = potTotal();
            w.stack += total;
            st.lastResult = w.name + " vince " + fmt(total);
            log(st.lastResult + " (gli altri hanno passato)");
            endHand();
            return;
        }
        if (roundComplete()) {
            finishStreet();
        } else {
            st.toActId = nextAfter(st.toActId, M_NEEDS_ACTION).id;
        }
    }

    /** Restituisce la parte di puntata che nessuno ha chiamato. */
    private void returnUncalled() {
        Player top = null;
        int max = 0;
        int second = 0;
        for (Player p : st.players) {
            if (!p.inHand) {
                continue;
            }
            if (p.bet > max) {
                second = max;
                max = p.bet;
                top = p;
            } else if (p.bet > second) {
                second = p.bet;
            }
        }
        if (top != null && max > second) {
            int r = max - second;
            top.bet -= r;
            top.contributed -= r;
            top.stack += r;
            if (top.stack > 0) {
                top.allIn = false;
            }
            log(fmt(r) + " non chiamate tornano a " + top.name);
        }
    }

    private void finishStreet() {
        returnUncalled();
        for (Player p : st.players) {
            p.bet = 0;
            p.acted = false;
            if (!p.folded && !p.allIn) {
                p.lastAction = "";
            }
        }
        st.currentBet = 0;
        st.minRaise = minBet();
        st.toActId = -1;
        if (st.street >= 3 || count(M_CAN_ACT) <= 1) {
            goShowdown();
            return;
        }
        st.street++;
        log(STREETS[st.street] + " — piatto " + fmt(potTotal()));
        st.toActId = nextAfter(st.dealerId, M_CAN_ACT).id;
    }

    private void goShowdown() {
        st.toActId = -1;
        st.pots = computePots();
        if (potTotal() == 0) {
            st.lastResult = "Mano finita senza puntate";
            log(st.lastResult);
            endHand();
            return;
        }
        st.stage = STAGE_SHOWDOWN;
        if (st.street < 3) {
            log("Tutti all-in: girate le carte rimanenti");
        }
        log("Showdown — piatto " + fmt(potTotal()) + ": il banco assegna la vincita");
    }

    /** Divide il piatto in principale e laterali in base agli all-in. */
    ArrayList<Pot> computePots() {
        int n = st.players.size();
        int[] rem = new int[n];
        for (int i = 0; i < n; i++) {
            rem[i] = st.players.get(i).contributed;
        }
        ArrayList<Pot> pots = new ArrayList<Pot>();
        while (true) {
            int level = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                Player p = st.players.get(i);
                if (p.inHand && !p.folded && rem[i] > 0) {
                    level = Math.min(level, rem[i]);
                }
            }
            if (level == Integer.MAX_VALUE) {
                break;
            }
            int amount = 0;
            ArrayList<Integer> elig = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) {
                Player p = st.players.get(i);
                if (rem[i] <= 0) {
                    continue;
                }
                if (p.inHand && !p.folded) {
                    elig.add(p.id);
                }
                int take = Math.min(rem[i], level);
                amount += take;
                rem[i] -= take;
            }
            int[] e = new int[elig.size()];
            for (int i = 0; i < e.length; i++) {
                e[i] = elig.get(i);
            }
            Pot last = pots.isEmpty() ? null : pots.get(pots.size() - 1);
            if (last != null && Arrays.equals(last.eligible, e)) {
                last.amount += amount;
            } else {
                pots.add(new Pot(amount, e));
            }
        }
        int leftover = 0;
        for (int r : rem) {
            leftover += r;
        }
        if (leftover > 0) {
            if (pots.isEmpty()) {
                ArrayList<Integer> elig = new ArrayList<Integer>();
                for (Player p : st.players) {
                    if (p.inHand && !p.folded) {
                        elig.add(p.id);
                    }
                }
                int[] e = new int[elig.size()];
                for (int i = 0; i < e.length; i++) {
                    e[i] = elig.get(i);
                }
                pots.add(new Pot(leftover, e));
            } else {
                pots.get(pots.size() - 1).amount += leftover;
            }
        }
        return pots;
    }

    /**
     * Assegna i piatti dello showdown.
     *
     * @param winners per ogni piatto (stesso ordine di {@code pots}) gli id dei vincitori;
     *                più vincitori = piatto diviso.
     */
    public synchronized void award(int[][] winners) {
        if (st.stage != STAGE_SHOWDOWN) {
            throw new GameException("Non siamo allo showdown");
        }
        if (winners == null || winners.length != st.pots.size()) {
            throw new GameException("Scegli i vincitori di ogni piatto");
        }
        for (int i = 0; i < winners.length; i++) {
            if (winners[i] == null || winners[i].length == 0) {
                throw new GameException("Scegli chi vince " + potName(i));
            }
            for (int k = 0; k < winners[i].length; k++) {
                if (!contains(st.pots.get(i).eligible, winners[i][k])) {
                    throw new GameException("Giocatore non in gara per " + potName(i));
                }
                for (int j = 0; j < k; j++) {
                    if (winners[i][j] == winners[i][k]) {
                        throw new GameException("Vincitore ripetuto");
                    }
                }
            }
        }
        pushUndo("Assegnazione del piatto");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < winners.length; i++) {
            Pot pot = st.pots.get(i);
            List<Player> ws = orderFromButton(winners[i]);
            int share = pot.amount / ws.size();
            int odd = pot.amount % ws.size();
            StringBuilder names = new StringBuilder();
            for (int k = 0; k < ws.size(); k++) {
                Player w = ws.get(k);
                w.stack += share + (k < odd ? 1 : 0);
                if (k > 0) {
                    names.append(k == ws.size() - 1 ? " e " : ", ");
                }
                names.append(w.name);
            }
            String line = ws.size() == 1
                    ? names + " vince " + fmt(pot.amount)
                    : names + " dividono " + fmt(pot.amount);
            if (st.pots.size() > 1) {
                line += " (" + potName(i) + ")";
            }
            log(line);
            if (result.length() > 0) {
                result.append(" · ");
            }
            result.append(line);
        }
        st.lastResult = result.toString();
        endHand();
        changed();
    }

    private String potName(int i) {
        return i == 0 ? "piatto principale" : "piatto laterale " + i;
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    /** Vincitori ordinati dal primo a sinistra del mazziere (riceve la fiche dispari). */
    private List<Player> orderFromButton(int[] ids) {
        ArrayList<Player> out = new ArrayList<Player>();
        int n = st.players.size();
        int start = indexOf(st.dealerId);
        for (int k = 1; k <= n; k++) {
            Player p = st.players.get(((start + k) % n + n) % n);
            if (contains(ids, p.id)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Annulla la mano in corso restituendo a tutti quanto messo nel piatto. */
    public synchronized void cancelHand() {
        if (st.stage == STAGE_IDLE) {
            throw new GameException("Nessuna mano in corso");
        }
        pushUndo("Annullamento della mano");
        for (Player p : st.players) {
            p.stack += p.contributed;
        }
        st.lastResult = "Mano #" + st.handNumber + " annullata: fiches restituite";
        log(st.lastResult);
        endHand();
        changed();
    }

    private void endHand() {
        for (Player p : st.players) {
            p.resetHand();
            if (p.pendingChips > 0) {
                p.stack += p.pendingChips;
                p.pendingChips = 0;
            }
        }
        st.stage = STAGE_IDLE;
        st.street = 0;
        st.toActId = -1;
        st.currentBet = 0;
        st.sbId = -1;
        st.bbId = -1;
        st.pots.clear();
    }

    // ------------------------------------------------------------------
    // Annulla
    // ------------------------------------------------------------------

    private void pushUndo(String label) {
        undo.push(new Snapshot(serialize(st), label));
        while (undo.size() > MAX_UNDO) {
            undo.removeLast();
        }
    }

    public synchronized String undo() {
        if (undo.isEmpty()) {
            throw new GameException("Niente da annullare");
        }
        Snapshot s = undo.pop();
        State restored = (State) deserialize(s.data);
        // Chi si è seduto dopo lo snapshot resta al tavolo; nomi e presenza non si annullano.
        for (Player cur : st.players) {
            Player old = null;
            for (Player p : restored.players) {
                if (p.id == cur.id) {
                    old = p;
                }
            }
            if (old == null) {
                for (Iterator<Player> it = restored.departed.iterator(); it.hasNext(); ) {
                    if (it.next().id == cur.id) {
                        it.remove();
                    }
                }
                cur.resetHand();
                restored.players.add(cur);
            } else {
                old.name = cur.name;
                old.lastSeen = cur.lastSeen;
                old.online = cur.online;
            }
        }
        for (Player p : restored.players) {
            if (p.lastSeen == 0) {
                // Giocatore tornato al tavolo grazie all'annulla.
                p.lastSeen = System.currentTimeMillis();
            }
        }
        restored.nextId = Math.max(restored.nextId, st.nextId);
        st = restored;
        log("↶ Annullato: " + s.label);
        changed();
        return s.label;
    }

    // ------------------------------------------------------------------
    // Impostazioni e reset
    // ------------------------------------------------------------------

    private static int parseInt(String v, String what, int min, int max) {
        try {
            int n = Integer.parseInt(v.trim());
            if (n < min || n > max) {
                throw new GameException(what + ": valore non valido");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new GameException(what + ": scrivi un numero intero");
        }
    }

    static int[] parseChips(String v) {
        String[] parts = v.split("[,;\\s]+");
        ArrayList<Integer> list = new ArrayList<Integer>();
        for (String s : parts) {
            if (s.isEmpty()) {
                continue;
            }
            int c = parseInt(s, "Tagli fiches", 1, MAX_CHIPS);
            if (!list.contains(c)) {
                list.add(c);
            }
        }
        if (list.isEmpty() || list.size() > 8) {
            throw new GameException("Tagli fiches: da 1 a 8 valori, es. 5,25,100,500");
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        Arrays.sort(out);
        return out;
    }

    public synchronized void updateSettings(Map<String, String> m) {
        Settings n = (Settings) deserialize(serialize(cfg));
        if (m.containsKey("tableName")) {
            String name = m.get("tableName").trim();
            if (name.isEmpty() || name.length() > 40) {
                throw new GameException("Nome del tavolo non valido");
            }
            n.tableName = name;
        }
        if (m.containsKey("startingStack")) {
            n.startingStack = parseInt(m.get("startingStack"), "Stack iniziale", 0, MAX_CHIPS);
        }
        if (m.containsKey("smallBlind")) {
            n.smallBlind = parseInt(m.get("smallBlind"), "Small blind", 0, MAX_CHIPS);
        }
        if (m.containsKey("bigBlind")) {
            n.bigBlind = parseInt(m.get("bigBlind"), "Big blind", 0, MAX_CHIPS);
        }
        if (m.containsKey("ante")) {
            n.ante = parseInt(m.get("ante"), "Ante", 0, MAX_CHIPS);
        }
        if (m.containsKey("autoBlinds")) {
            n.autoBlinds = "1".equals(m.get("autoBlinds")) || "true".equals(m.get("autoBlinds"));
        }
        if (m.containsKey("chips")) {
            n.chips = parseChips(m.get("chips"));
        }
        if (m.containsKey("chipValue")) {
            try {
                double d = Double.parseDouble(m.get("chipValue").trim().replace(',', '.'));
                if (d < 0 || d > 1_000_000 || Double.isNaN(d)) {
                    throw new NumberFormatException();
                }
                n.chipValue = d;
            } catch (NumberFormatException e) {
                throw new GameException("Valore fiche non valido");
            }
        }
        if (m.containsKey("currency")) {
            String c = m.get("currency").trim();
            if (c.length() > 5) {
                throw new GameException("Valuta: massimo 5 caratteri");
            }
            n.currency = c;
        }
        if (m.containsKey("locked")) {
            n.locked = "1".equals(m.get("locked")) || "true".equals(m.get("locked"));
        }
        if (n.smallBlind > n.bigBlind) {
            throw new GameException("Lo small blind non può superare il big blind");
        }
        boolean blindsChanged = n.smallBlind != cfg.smallBlind || n.bigBlind != cfg.bigBlind || n.ante != cfg.ante;
        cfg = n;
        if (blindsChanged) {
            log("Nuovi bui " + fmt(n.smallBlind) + "/" + fmt(n.bigBlind)
                    + (n.ante > 0 ? " ante " + fmt(n.ante) : "") + " (dalla prossima mano)");
        }
        changed();
    }

    /**
     * Ricomincia la partita.
     *
     * @param keepPlayers true = stessi giocatori con lo stack iniziale; false = tavolo vuoto
     */
    public synchronized void reset(boolean keepPlayers) {
        State n = new State();
        if (keepPlayers) {
            n.nextId = st.nextId;
            for (Player p : st.players) {
                p.resetHand();
                p.stack = cfg.startingStack;
                p.buyIn = cfg.startingStack;
                p.cashOut = 0;
                p.pendingChips = 0;
                n.players.add(p);
            }
        }
        st = n;
        undo.clear();
        log.clear();
        log(keepPlayers ? "Nuova partita: tutti ripartono da " + fmt(cfg.startingStack) : "Tavolo svuotato");
        changed();
    }

    // ------------------------------------------------------------------
    // Presenza e attesa delle modifiche (long polling)
    // ------------------------------------------------------------------

    public synchronized void touch(Player p) {
        p.lastSeen = System.currentTimeMillis();
        if (!p.online) {
            p.online = true;
            bump();
        }
    }

    public synchronized void sweepPresence() {
        long now = System.currentTimeMillis();
        boolean any = false;
        for (Player p : st.players) {
            boolean on = now - p.lastSeen < ONLINE_TIMEOUT_MS;
            if (on != p.online) {
                p.online = on;
                any = true;
            }
        }
        if (any) {
            bump();
        }
    }

    /** Attende finché la versione è ancora {@code since} (max {@code timeoutMs}). */
    public synchronized void awaitChange(long since, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (version == since) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            wait(left);
        }
    }

    public synchronized long version() {
        return version;
    }

    private void bump() {
        version++;
        notifyAll();
    }

    private void changed() {
        dirty = true;
        bump();
    }

    private void log(String text) {
        log.add(new LogEntry(++logSeq, st.handNumber, text));
        while (log.size() > MAX_LOG) {
            log.remove(0);
        }
    }

    static String fmt(int n) {
        return String.format(Locale.ITALIAN, "%,d", n);
    }

    // Accesso allo stato per i test.
    synchronized int stage() {
        return st.stage;
    }

    synchronized int street() {
        return st.street;
    }

    synchronized int toActId() {
        return st.toActId;
    }

    synchronized int dealerId() {
        return st.dealerId;
    }

    synchronized int currentBet() {
        return st.currentBet;
    }

    synchronized int pot() {
        return potTotal();
    }

    synchronized List<Pot> pots() {
        return new ArrayList<Pot>(st.pots);
    }

    synchronized List<Player> players() {
        return new ArrayList<Player>(st.players);
    }

    // ------------------------------------------------------------------
    // Stato JSON per i client
    // ------------------------------------------------------------------

    public synchronized String stateJson(Player me, boolean admin, List<String> urls) {
        Json j = new Json().obj();
        j.put("v", version).put("sid", instanceId).put("admin", admin);
        j.key("me");
        if (me != null) {
            j.val(me.id);
        } else {
            j.nul();
        }

        j.key("cfg").obj()
                .put("tableName", cfg.tableName)
                .put("startingStack", cfg.startingStack)
                .put("smallBlind", cfg.smallBlind)
                .put("bigBlind", cfg.bigBlind)
                .put("ante", cfg.ante)
                .put("autoBlinds", cfg.autoBlinds)
                .put("chipValue", cfg.chipValue)
                .put("currency", cfg.currency)
                .put("locked", cfg.locked);
        j.key("chips").arr();
        for (int c : cfg.chips) {
            j.val(c);
        }
        j.endArr().end();

        String stage = st.stage == STAGE_BETTING ? "betting" : st.stage == STAGE_SHOWDOWN ? "showdown" : "idle";
        int total = potTotal();
        j.key("hand").obj()
                .put("n", st.handNumber)
                .put("stage", stage)
                .put("street", st.street)
                .put("streetName", STREETS[st.street])
                .put("dealer", st.dealerId)
                .put("dealerFixed", st.dealerFixed)
                .put("sb", st.sbId)
                .put("bb", st.bbId)
                .put("toAct", st.toActId)
                .put("currentBet", st.currentBet)
                .put("minRaise", st.minRaise)
                .put("potTotal", total)
                .put("pot", total - betsOnTable())
                .put("lastResult", st.lastResult);
        j.key("pots").arr();
        if (st.stage == STAGE_SHOWDOWN) {
            for (Pot pot : st.pots) {
                j.obj().put("amount", pot.amount).key("eligible").arr();
                for (int id : pot.eligible) {
                    j.val(id);
                }
                j.endArr().end();
            }
        }
        j.endArr();
        Player turn = st.stage == STAGE_BETTING ? byId(st.toActId) : null;
        j.key("options");
        if (turn != null) {
            int toCall = Math.max(0, st.currentBet - turn.bet);
            int maxTo = turn.bet + turn.stack;
            j.obj()
                    .put("toCall", Math.min(toCall, turn.stack))
                    .put("canCheck", toCall == 0)
                    .put("canRaise", canRaise(turn))
                    .put("isBet", st.currentBet == 0)
                    .put("minTo", Math.min(st.currentBet + st.minRaise, maxTo))
                    .put("maxTo", maxTo)
                    .end();
        } else {
            j.nul();
        }
        j.end();

        j.key("players").arr();
        for (Player p : st.players) {
            writePlayer(j, p);
        }
        j.endArr();
        j.key("departed").arr();
        for (Player p : st.departed) {
            j.obj().put("id", p.id).put("name", p.name).put("buyIn", p.buyIn).put("cashOut", p.cashOut).end();
        }
        j.endArr();

        j.key("log").arr();
        int from = Math.max(0, log.size() - 60);
        for (int i = from; i < log.size(); i++) {
            LogEntry e = log.get(i);
            j.obj().put("s", e.seq).put("h", e.hand).put("t", e.text).end();
        }
        j.endArr();

        if (admin) {
            j.put("undo", undo.isEmpty() ? "" : undo.peek().label);
            j.key("urls").arr();
            if (urls != null) {
                for (String u : urls) {
                    j.val(u);
                }
            }
            j.endArr();
        }
        j.end();
        return j.toString();
    }

    private static void writePlayer(Json j, Player p) {
        j.obj()
                .put("id", p.id)
                .put("name", p.name)
                .put("stack", p.stack)
                .put("bet", p.bet)
                .put("contributed", p.contributed)
                .put("inHand", p.inHand)
                .put("folded", p.folded)
                .put("allIn", p.allIn)
                .put("sittingOut", p.sittingOut)
                .put("online", p.online)
                .put("lastAction", p.lastAction)
                .put("buyIn", p.buyIn)
                .put("cashOut", p.cashOut)
                .put("pending", p.pendingChips)
                .end();
    }

    // ------------------------------------------------------------------
    // Salvataggio su file (la partita sopravvive al riavvio dell'app)
    // ------------------------------------------------------------------

    private static byte[] serialize(Object o) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(bos);
            out.writeObject(o);
            out.close();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object deserialize(byte[] data) {
        try {
            ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data));
            return in.readObject();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] saveBytes() {
        Saved s = new Saved();
        s.cfg = cfg;
        s.st = st;
        s.log = log;
        s.logSeq = logSeq;
        return serialize(s);
    }

    /** Carica una partita salvata; restituisce false se il file non c'è o è illeggibile. */
    public synchronized boolean load(File f) {
        if (!f.isFile()) {
            return false;
        }
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                bos.write(buf, 0, r);
            }
            in.close();
            Saved s = (Saved) deserialize(bos.toByteArray());
            cfg = s.cfg;
            st = s.st;
            log = s.log;
            logSeq = s.logSeq;
            long now = System.currentTimeMillis();
            for (Player p : st.players) {
                // Dopo un riavvio diamo ai giocatori il tempo di riconnettersi.
                p.lastSeen = now - ONLINE_TIMEOUT_MS + 10_000;
                p.online = false;
            }
            log("Partita ripristinata dopo il riavvio del banco");
            changed();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Avvia un thread che salva la partita su file a ogni modifica. */
    public void enableAutosave(File f) {
        synchronized (this) {
            saveFile = f;
            if (saverRunning) {
                return;
            }
            saverRunning = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                saverLoop();
            }
        }, "salvataggio-partita");
        t.setDaemon(true);
        t.start();
    }

    public void stopAutosave() {
        synchronized (this) {
            saverRunning = false;
            notifyAll();
        }
        saveNow();
    }

    private void saverLoop() {
        while (true) {
            byte[] data;
            File f;
            synchronized (this) {
                while (!dirty && saverRunning) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (!saverRunning) {
                    return;
                }
                dirty = false;
                data = saveBytes();
                f = saveFile;
            }
            writeFile(f, data);
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public void saveNow() {
        byte[] data;
        File f;
        synchronized (this) {
            if (saveFile == null) {
                return;
            }
            dirty = false;
            data = saveBytes();
            f = saveFile;
        }
        writeFile(f, data);
    }

    private static synchronized void writeFile(File f, byte[] data) {
        File tmp = new File(f.getPath() + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(data);
            out.getFD().sync();
            out.close();
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (IOException ignored) {
            // Salvataggio best-effort: la partita continua in memoria.
        }
    }
}
