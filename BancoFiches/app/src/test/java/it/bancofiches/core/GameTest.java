package it.bancofiches.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class GameTest {

    private static Game game(String sb, String bb) {
        Game g = new Game();
        Map<String, String> m = new HashMap<String, String>();
        m.put("smallBlind", sb);
        m.put("bigBlind", bb);
        g.updateSettings(m);
        return g;
    }

    private static Player join(Game g, String name, int stack) {
        Player p = g.join(name);
        g.setStack(p.id, stack);
        return p;
    }

    private static void expectError(Runnable r) {
        try {
            r.run();
            fail("Doveva fallire");
        } catch (GameException expected) {
            // ok
        }
    }

    private static int total(Game g) {
        int t = 0;
        for (Player p : g.players()) {
            t += p.stack + p.contributed + p.pendingChips;
        }
        return t;
    }

    @Test
    public void blindsAndPreflopOrder() {
        Game g = game("5", "10");
        Player a = join(g, "Anna", 1000);
        Player b = join(g, "Bruno", 1000);
        Player c = join(g, "Carla", 1000);
        g.startHand();
        assertEquals(a.id, g.dealerId());
        assertEquals(995, b.stack);
        assertEquals(990, c.stack);
        assertEquals(10, g.currentBet());
        assertEquals(a.id, g.toActId());
    }

    @Test
    public void foldsToBigBlind() {
        Game g = game("5", "10");
        Player a = join(g, "Anna", 1000);
        Player b = join(g, "Bruno", 1000);
        Player c = join(g, "Carla", 1000);
        g.startHand();
        g.act(a.id, "fold", 0, false);
        g.act(b.id, "fold", 0, false);
        assertEquals(Game.STAGE_IDLE, g.stage());
        assertEquals(1000, a.stack);
        assertEquals(995, b.stack);
        assertEquals(1005, c.stack);
        assertEquals(3000, total(g));
    }

    @Test
    public void notYourTurn() {
        final Game g = game("5", "10");
        join(g, "Anna", 1000);
        final Player b = join(g, "Bruno", 1000);
        join(g, "Carla", 1000);
        g.startHand();
        expectError(new Runnable() {
            public void run() {
                g.act(b.id, "call", 0, false);
            }
        });
    }

    @Test
    public void checkDownToShowdown() {
        Game g = game("5", "10");
        Player a = join(g, "Anna", 1000);
        Player b = join(g, "Bruno", 1000);
        Player c = join(g, "Carla", 1000);
        g.startHand();
        g.act(a.id, "call", 0, false);
        g.act(b.id, "call", 0, false);
        assertEquals(c.id, g.toActId()); // opzione del big blind
        g.act(c.id, "check", 0, false);
        assertEquals(1, g.street());
        assertEquals(b.id, g.toActId()); // dopo il flop parte il primo a sinistra del mazziere
        for (int street = 1; street <= 3; street++) {
            g.act(b.id, "check", 0, false);
            g.act(c.id, "check", 0, false);
            g.act(a.id, "check", 0, false);
        }
        assertEquals(Game.STAGE_SHOWDOWN, g.stage());
        List<Game.Pot> pots = g.pots();
        assertEquals(1, pots.size());
        assertEquals(30, pots.get(0).amount);
        assertEquals(3, pots.get(0).eligible.length);
        g.award(new int[][]{{a.id}});
        assertEquals(1020, a.stack);
        assertEquals(990, b.stack);
        assertEquals(990, c.stack);
        assertEquals(Game.STAGE_IDLE, g.stage());
    }

    @Test
    public void minRaiseAndReopen() {
        final Game g = game("5", "10");
        final Player a = join(g, "Anna", 1000);
        Player b = join(g, "Bruno", 1000);
        Player c = join(g, "Carla", 1000);
        g.startHand();
        expectError(new Runnable() {
            public void run() {
                g.act(a.id, "raise", 15, false); // minimo 20
            }
        });
        g.act(a.id, "raise", 30, false);
        assertEquals(30, g.currentBet());
        g.act(b.id, "raise", 60, false); // rilancio di 30
        g.act(c.id, "fold", 0, false);
        assertEquals(a.id, g.toActId());
        expectError(new Runnable() {
            public void run() {
                g.act(a.id, "raise", 80, false); // rilancio minimo ora 30 -> 90
            }
        });
        g.act(a.id, "call", 0, false);
        assertEquals(1, g.street());
        assertEquals(130, g.pot());
    }

    @Test
    public void sidePots() {
        Game g = game("5", "10");
        Player a = join(g, "Anna", 100);
        Player b = join(g, "Bruno", 300);
        Player c = join(g, "Carla", 1000);
        g.startHand(); // mazziere Anna, SB Bruno, BB Carla
        g.act(a.id, "allin", 0, false);
        g.act(b.id, "allin", 0, false);
        g.act(c.id, "call", 0, false);
        assertEquals(Game.STAGE_SHOWDOWN, g.stage());
        List<Game.Pot> pots = g.pots();
        assertEquals(2, pots.size());
        assertEquals(300, pots.get(0).amount);
        assertEquals(3, pots.get(0).eligible.length);
        assertEquals(400, pots.get(1).amount);
        assertArrayEquals(new int[]{b.id, c.id}, pots.get(1).eligible);
        g.award(new int[][]{{a.id}, {c.id}});
        assertEquals(300, a.stack);
        assertEquals(0, b.stack);
        assertEquals(1100, c.stack);
        assertEquals(1400, total(g));
    }

    @Test
    public void uncalledBetReturned() {
        Game g = game("5", "10");
        Player a = join(g, "Anna", 1000);
        Player b = join(g, "Bruno", 200);
        g.startHand(); // testa a testa: Anna mazziere e small blind, agisce per prima
        assertEquals(a.id, g.toActId());
        g.act(a.id, "allin", 0, false);
        g.act(b.id, "call", 0, false);
        assertEquals(Game.STAGE_SHOWDOWN, g.stage());
        assertEquals(400, g.pot());
        assertEquals(800, a.stack);
        g.award(new int[][]{{b.id}});
        assertEquals(400, b.stack);
        assertEquals(800, a.stack);
    }

    @Test
    public void shortAllInDoesNotReopenBetting() {
        final Game g = new Game();
        Map<String, String> m = new HashMap<String, String>();
        m.put("autoBlinds", "0");
        m.put("bigBlind", "10");
        m.put("smallBlind", "5");
        g.updateSettings(m);
        Player d = join(g, "Dario", 1000); // mazziere
        final Player a = join(g, "Anna", 1000);
        Player b = join(g, "Bruno", 1000);
        Player c = join(g, "Carla", 150);
        g.startHand();
        assertEquals(a.id, g.toActId());
        g.act(a.id, "raise", 100, false);
        g.act(b.id, "call", 0, false);
        g.act(c.id, "allin", 0, false); // 150: rilancio incompleto
        g.act(d.id, "fold", 0, false);
        assertEquals(a.id, g.toActId());
        expectError(new Runnable() {
            public void run() {
                g.act(a.id, "raise", 400, false);
            }
        });
        g.act(a.id, "call", 0, false);
        g.act(b.id, "call", 0, false);
        assertEquals(1, g.street());
        assertEquals(450, g.pot());
    }

    @Test
    public void headsUpPostflopOrder() {
        Game g = game("5", "10");
        Player a = join(g, "Anna", 1000);
        Player b = join(g, "Bruno", 1000);
        g.startHand();
        assertEquals(995, a.stack); // mazziere = small blind
        assertEquals(990, b.stack);
        g.act(a.id, "call", 0, false);
        g.act(b.id, "check", 0, false);
        assertEquals(1, g.street());
        assertEquals(b.id, g.toActId());
    }

    @Test
    public void dealerRotates() {
        Game g = game("5", "10");
        Player a = join(g, "Anna", 1000);
        Player b = join(g, "Bruno", 1000);
        Player c = join(g, "Carla", 1000);
        g.startHand();
        g.cancelHand();
        assertEquals(1000, a.stack);
        assertEquals(1000, b.stack);
        assertEquals(1000, c.stack);
        g.startHand();
        assertEquals(b.id, g.dealerId());
        g.cancelHand();
        g.setDealer(a.id);
        g.startHand();
        assertEquals(a.id, g.dealerId());
    }

    @Test
    public void undoRestoresStateButKeepsNewPlayers() {
        Game g = game("5", "10");
        Player a = join(g, "Anna", 1000);
        Player b = join(g, "Bruno", 1000);
        Player c = join(g, "Carla", 1000);
        g.startHand();
        g.act(a.id, "raise", 50, false);
        Player late = g.join("Ultimo");
        g.undo();
        List<Player> ps = g.players();
        assertEquals(4, ps.size());
        Player a2 = ps.get(0);
        assertEquals(1000, a2.stack);
        assertEquals(a.id, g.toActId());
        assertEquals(10, g.currentBet());
        assertNotNull(g.byToken(late.token));
        assertEquals(4000 - 1000 + 1000, total(g));
        assertFalse(ps.get(3).inHand);
        g.undo(); // annulla la nuova mano
        assertEquals(Game.STAGE_IDLE, g.stage());
        assertEquals(1000, g.players().get(1).stack);
    }

    @Test
    public void splitPotOddChipGoesLeftOfButton() {
        Game g = new Game();
        Map<String, String> m = new HashMap<String, String>();
        m.put("autoBlinds", "0");
        g.updateSettings(m);
        Player a = join(g, "Anna", 1000); // mazziere
        Player b = join(g, "Bruno", 1000);
        Player c = join(g, "Carla", 1000);
        g.startHand();
        g.act(b.id, "raise", 11, false);
        g.act(c.id, "call", 0, false);
        g.act(a.id, "call", 0, false);
        for (int s = 1; s <= 3; s++) {
            g.act(b.id, "check", 0, false);
            g.act(c.id, "check", 0, false);
            g.act(a.id, "check", 0, false);
        }
        assertEquals(33, g.pot());
        g.award(new int[][]{{a.id, c.id}});
        assertEquals(989 + 16, a.stack);
        assertEquals(989 + 17, c.stack); // Carla è più vicina alla sinistra del mazziere
    }

    @Test
    public void rebuyDuringHandIsPending() {
        Game g = game("5", "10");
        Player a = join(g, "Anna", 1000);
        Player b = join(g, "Bruno", 1000);
        g.startHand();
        g.rebuy(a.id, 500);
        assertEquals(995, a.stack);
        assertEquals(500, a.pendingChips);
        assertEquals(1500, a.buyIn);
        g.act(a.id, "fold", 0, false);
        assertEquals(1495, a.stack);
        assertEquals(1005, b.stack);
    }

    @Test
    public void joinRules() {
        final Game g = new Game();
        g.join("Anna");
        expectError(new Runnable() {
            public void run() {
                g.join("  anna ");
            }
        });
        expectError(new Runnable() {
            public void run() {
                g.join("   ");
            }
        });
        Map<String, String> m = new HashMap<String, String>();
        m.put("locked", "1");
        g.updateSettings(m);
        expectError(new Runnable() {
            public void run() {
                g.join("Bruno");
            }
        });
        assertEquals("Mario Rossi", Game.cleanName("  Mario \n  Rossi "));
    }

    @Test
    public void cannotRemovePlayerInHand() {
        final Game g = game("5", "10");
        final Player a = join(g, "Anna", 1000);
        Player b = join(g, "Bruno", 1000);
        g.startHand();
        expectError(new Runnable() {
            public void run() {
                g.removePlayer(a.id, true);
            }
        });
        g.act(a.id, "fold", 0, false);
        g.removePlayer(a.id, true);
        assertNull(g.byToken(a.token));
        assertEquals(1, g.players().size());
        assertTrue(g.stateJson(null, false, null).contains("\"departed\":[{\"id\":" + a.id));
        assertEquals(1005, b.stack);
    }

    @Test
    public void anteAndAllInFromBlind() {
        Game g = new Game();
        Map<String, String> m = new HashMap<String, String>();
        m.put("smallBlind", "50");
        m.put("bigBlind", "100");
        m.put("ante", "10");
        g.updateSettings(m);
        Player a = join(g, "Anna", 1000);
        Player b = join(g, "Bruno", 1000);
        Player c = join(g, "Carla", 60); // ante 10 + bb 50 = all-in
        g.startHand();
        assertTrue(c.allIn);
        assertEquals(30 + 50 + 50, g.pot());
        assertEquals(100, g.currentBet()); // gli altri devono comunque vedere il big blind intero
        g.act(a.id, "call", 0, false);
        g.act(b.id, "call", 0, false);
        // Carla è all-in: il giro finisce, si passa al flop
        assertEquals(1, g.street());
        assertEquals(110 + 110 + 60, g.pot());
    }

    @Test
    public void everyoneAllInPreflopGoesToShowdown() {
        Game g = game("5", "10");
        Player a = join(g, "Anna", 8);
        Player b = join(g, "Bruno", 4);
        g.startHand(); // Anna SB 5, Bruno BB 4 (all-in): Anna ha già coperto, nessuno deve agire
        assertEquals(Game.STAGE_SHOWDOWN, g.stage());
        assertEquals(8, g.pot()); // l'eccedenza di Anna (1) torna indietro
        assertEquals(4, a.stack);
    }

    @Test
    public void persistence() throws Exception {
        Game g = game("5", "10");
        Player a = join(g, "Anna", 1234);
        join(g, "Bruno", 1000);
        g.startHand();
        File f = File.createTempFile("banco", ".bin");
        g.enableAutosave(f);
        g.saveNow();
        Game g2 = new Game();
        assertTrue(g2.load(f));
        Player a2 = g2.byToken(a.token);
        assertNotNull(a2);
        assertEquals("Anna", a2.name);
        assertEquals(Game.STAGE_BETTING, g2.stage());
        assertEquals(a.stack, a2.stack);
        g.stopAutosave();
        f.delete();
    }

    @Test
    public void settingsValidation() {
        final Game g = new Game();
        final Map<String, String> m = new HashMap<String, String>();
        m.put("smallBlind", "20");
        m.put("bigBlind", "10");
        expectError(new Runnable() {
            public void run() {
                g.updateSettings(m);
            }
        });
        assertArrayEquals(new int[]{1, 5, 25}, Game.parseChips("25, 5,1 5"));
        Map<String, String> ok = new HashMap<String, String>();
        ok.put("chipValue", "0,05");
        ok.put("tableName", "Venerdì sera");
        g.updateSettings(ok);
        assertTrue(g.stateJson(null, false, null).contains("\"chipValue\":0.05"));
    }

    // ------------------------------------------------------------ blackjack

    private static Game blackjack() {
        Game g = new Game();
        Map<String, String> m = new HashMap<String, String>();
        m.put("game", "blackjack");
        m.put("bjMin", "10");
        m.put("bjMax", "500");
        g.updateSettings(m);
        return g;
    }

    private static Map<Integer, int[]> outcomes(Object... pairs) {
        Map<Integer, int[]> m = new HashMap<Integer, int[]>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((Integer) pairs[i], (int[]) pairs[i + 1]);
        }
        return m;
    }

    private static int totalWithHouse(Game g) {
        return total(g) + g.houseNet();
    }

    @Test
    public void blackjackBasicRound() {
        Game g = blackjack();
        Player a = join(g, "Anna", 1000);
        Player b = join(g, "Bruno", 1000);
        Player c = join(g, "Carla", 1000);
        join(g, "Dario", 1000); // non punta
        assertTrue(g.stateJson(null, false, null).contains("\"tableName\":\"Tavolo da blackjack\""));
        g.startHand();
        assertEquals(Game.STAGE_BJ_BETS, g.stage());
        g.bjBet(a.id, 50, false);
        g.bjBet(b.id, 30, false);
        g.bjBet(b.id, 20, false); // cambia idea
        g.bjBet(c.id, 10, false);
        assertEquals(980, b.stack);
        g.bjDeal();
        assertEquals(Game.STAGE_BJ_PLAY, g.stage());
        g.bjSettle(outcomes(a.id, new int[]{Player.BJ_WIN}, b.id, new int[]{Player.BJ_LOSE},
                c.id, new int[]{Player.BJ_BLACKJACK}), false);
        assertEquals(Game.STAGE_IDLE, g.stage());
        assertEquals(1050, a.stack);
        assertEquals(980, b.stack);
        assertEquals(1015, c.stack);
        assertEquals(20 - 50 - 15, g.houseNet());
        assertEquals(4000, totalWithHouse(g));
        assertEquals(50, a.lastBet);
    }

    @Test
    public void blackjackBetLimits() {
        final Game g = blackjack();
        final Player a = join(g, "Anna", 300);
        g.startHand();
        expectError(new Runnable() {
            public void run() {
                g.bjBet(a.id, 5, false);
            }
        });
        expectError(new Runnable() {
            public void run() {
                g.bjBet(a.id, 600, false);
            }
        });
        expectError(new Runnable() {
            public void run() {
                g.bjBet(a.id, 400, false);
            }
        });
        expectError(new Runnable() {
            public void run() {
                g.bjDeal(); // nessuna puntata
            }
        });
        g.bjBet(a.id, 300, false);
        assertEquals(0, a.stack);
        g.bjBet(a.id, 0, false);
        assertEquals(300, a.stack);
        assertFalse(a.inHand);
    }

    @Test
    public void blackjackSplitAndDouble() {
        final Game g = blackjack();
        final Player a = join(g, "Anna", 1000);
        g.startHand();
        g.bjBet(a.id, 50, false);
        g.bjDeal();
        g.bjSplit(a.id, 0, false);
        g.bjDouble(a.id, 1, false);
        assertEquals(850, a.stack); // 50 + 50 (split) + 50 (raddoppio della seconda)
        assertEquals(150, a.contributed);
        expectError(new Runnable() {
            public void run() {
                g.bjDouble(a.id, 1, false); // già raddoppiata
            }
        });
        expectError(new Runnable() {
            public void run() {
                g.bjSurrender(a.id, 0, false); // non dopo lo split
            }
        });
        expectError(new Runnable() {
            public void run() {
                g.bjSettle(outcomes(a.id, new int[]{Player.BJ_WIN}), false); // manca una mano
            }
        });
        g.bjSettle(outcomes(a.id, new int[]{Player.BJ_WIN, Player.BJ_LOSE}), false);
        assertEquals(950, a.stack); // +50 sulla prima, -100 sulla seconda
        assertEquals(50, g.houseNet());
    }

    @Test
    public void blackjackInsuranceAndSurrender() {
        Game g = blackjack();
        Player a = join(g, "Anna", 1000);
        Player b = join(g, "Bruno", 1000);
        g.startHand();
        g.bjBet(a.id, 50, false);
        g.bjBet(b.id, 51, false);
        g.bjDeal();
        g.bjInsure(a.id, false);
        g.bjSurrender(b.id, 0, false);
        assertEquals(925, a.stack);
        // Il banco ha blackjack: Anna perde la mano ma l'assicurazione paga 2:1 (pari).
        g.bjSettle(outcomes(a.id, new int[]{Player.BJ_LOSE}, b.id, new int[]{Player.BJ_LOSE}), true);
        assertEquals(1000, a.stack);
        assertEquals(1000 - 51 + 25, b.stack); // resa: torna metà (arrotondata per difetto)
        assertEquals(26, g.houseNet());
        assertEquals(2000, totalWithHouse(g));
    }

    @Test
    public void blackjackCancelUndoAndModeSwitch() {
        final Game g = blackjack();
        Player a = join(g, "Anna", 1000);
        g.startHand();
        g.bjBet(a.id, 100, false);
        g.bjDeal();
        g.bjDouble(a.id, 0, false);
        expectError(new Runnable() {
            public void run() {
                Map<String, String> m = new HashMap<String, String>();
                m.put("game", "poker");
                g.updateSettings(m);
            }
        });
        g.cancelHand();
        assertEquals(1000, g.players().get(0).stack);
        g.startHand();
        g.bjBet(a.id, 100, false);
        g.bjDeal();
        g.bjSettle(outcomes(a.id, new int[]{Player.BJ_PUSH}), false);
        assertEquals(1000, g.players().get(0).stack);
        g.undo(); // torna prima del pagamento
        assertEquals(Game.STAGE_BJ_PLAY, g.stage());
        assertEquals(900, g.players().get(0).stack);
        assertTrue(g.stateJson(null, false, null).contains("\"hands\":[{\"bet\":100"));
    }

    @Test
    public void blackjackLeaveDuringBetsRefunds() {
        Game g = blackjack();
        Player a = join(g, "Anna", 1000);
        join(g, "Bruno", 1000);
        g.startHand();
        g.bjBet(a.id, 100, false);
        g.removePlayer(a.id, false);
        assertTrue(g.stateJson(null, false, null).contains("\"cashOut\":1000"));
    }

    @Test
    public void parseOutcomes() {
        Map<Integer, int[]> o = FicheServer.parseOutcomes("3=W,L;5=B");
        assertArrayEquals(new int[]{Player.BJ_WIN, Player.BJ_LOSE}, o.get(3));
        assertArrayEquals(new int[]{Player.BJ_BLACKJACK}, o.get(5));
    }

    @Test
    public void jsonEscaping() {
        assertEquals("\"a\\\"b\\\\c\\n\\u003c\"", Json.quote("a\"b\\c\n<"));
        String s = new Json().obj().put("x", 1).key("y").arr().val(1).val("z").endArr().put("w", true).end().toString();
        assertEquals("{\"x\":1,\"y\":[1,\"z\"],\"w\":true}", s);
    }

    @Test
    public void parseWinners() {
        int[][] w = FicheServer.parseWinners("1,3;2");
        assertArrayEquals(new int[]{1, 3}, w[0]);
        assertArrayEquals(new int[]{2}, w[1]);
        assertEquals(0, FicheServer.parseWinners("1;")[1].length);
    }
}
