package it.bancofiches.core;

/**
 * Scrittore JSON minimale (solo serializzazione): gestisce da solo le virgole
 * tra elementi, così il codice che costruisce lo stato resta leggibile.
 */
public final class Json {
    private final StringBuilder sb = new StringBuilder(1024);
    /** true se il prossimo elemento al livello corrente è il primo. */
    private final boolean[] first = new boolean[64];
    private int depth = 0;
    private boolean afterKey = false;

    public Json() {
        first[0] = true;
    }

    private void sep() {
        if (afterKey) {
            afterKey = false;
            return;
        }
        if (!first[depth]) {
            sb.append(',');
        }
        first[depth] = false;
    }

    public Json obj() {
        sep();
        sb.append('{');
        first[++depth] = true;
        return this;
    }

    public Json end() {
        sb.append('}');
        depth--;
        return this;
    }

    public Json arr() {
        sep();
        sb.append('[');
        first[++depth] = true;
        return this;
    }

    public Json endArr() {
        sb.append(']');
        depth--;
        return this;
    }

    public Json key(String k) {
        sep();
        quote(sb, k);
        sb.append(':');
        afterKey = true;
        return this;
    }

    public Json val(String v) {
        sep();
        if (v == null) {
            sb.append("null");
        } else {
            quote(sb, v);
        }
        return this;
    }

    public Json val(long v) {
        sep();
        sb.append(v);
        return this;
    }

    public Json val(double v) {
        sep();
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            sb.append('0');
        } else if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            sb.append((long) v);
        } else {
            sb.append(v);
        }
        return this;
    }

    public Json val(boolean v) {
        sep();
        sb.append(v);
        return this;
    }

    public Json nul() {
        sep();
        sb.append("null");
        return this;
    }

    /** Inserisce JSON già serializzato. */
    public Json raw(String json) {
        sep();
        sb.append(json);
        return this;
    }

    public Json put(String k, String v) {
        return key(k).val(v);
    }

    public Json put(String k, long v) {
        return key(k).val(v);
    }

    public Json put(String k, double v) {
        return key(k).val(v);
    }

    public Json put(String k, boolean v) {
        return key(k).val(v);
    }

    @Override
    public String toString() {
        return sb.toString();
    }

    public static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        quote(b, s);
        return b.toString();
    }

    static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    // '<' escapato per poter incorporare il JSON in HTML senza rischi.
                    if (c < 0x20 || c == '<' || c == 0x2028 || c == 0x2029) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }
}
