package com.synseria.synema;

import android.content.Context;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * The design system's text roles and sizes (styles.xml, jetons.xml) for code that builds its views, and the
 * app's accent: the one place that applies the chosen preset to a context's theme and reads it back.
 */
final class Tokens {
    /** An accent preset: its id in config.json, its style in jetons.xml and its name. */
    static final class Colour {
        final String id;
        final int style;
        final int label;

        private Colour(String id, int style, int label) {
            this.id = id;
            this.style = style;
            this.label = label;
        }
    }

    /** The design system's six presets; the first is the default. */
    static final Colour[] COLOURS = {
            new Colour("azur", R.style.SynCouleur_Azur, R.string.colour_azur),
            new Colour("jade", R.style.SynCouleur_Jade, R.string.colour_jade),
            new Colour("violet", R.style.SynCouleur_Violet, R.string.colour_violet),
            new Colour("framboise", R.style.SynCouleur_Framboise, R.string.colour_framboise),
            new Colour("corail", R.style.SynCouleur_Corail, R.string.colour_corail),
            new Colour("ardoise", R.style.SynCouleur_Ardoise, R.string.colour_ardoise),
    };

    private Tokens() {
    }

    /** The preset with this id, or null. */
    static Colour colour(String id) {
        for (Colour c : COLOURS) if (c.id.equals(id)) return c;
        return null;
    }

    /** Puts the chosen accent on the context's theme; every window and service does this once, in Scale.wrap. */
    static void apply(Context ctx) {
        Colour c = colour(Config.load(ctx).colour);
        ctx.getTheme().applyStyle((c != null ? c : COLOURS[0]).style, true);
    }

    /** The accent: fills, switches, the first curve. */
    static int primary(Context ctx) {
        return attr(ctx, R.attr.syn_primaire);
    }

    /** The accent as ink on the dark surface: text buttons, links. */
    static int primaryInk(Context ctx) {
        return attr(ctx, R.attr.syn_primaire_encre);
    }

    /** A preset's own accent, whichever one the context wears: for its swatch. */
    static int preset(Context ctx, Colour c) {
        android.content.res.Resources.Theme t = ctx.getResources().newTheme();
        t.applyStyle(c.style, true);
        TypedValue v = new TypedValue();
        t.resolveAttribute(R.attr.syn_primaire, v, true);
        return v.data;
    }

    private static int attr(Context ctx, int attr) {
        TypedValue v = new TypedValue();
        ctx.getTheme().resolveAttribute(attr, v, true);
        return v.data;
    }

    /** A text in a role (R.style.Role_*), colour and size included. */
    static TextView text(Context ctx, CharSequence s, int role) {
        TextView t = new TextView(ctx, null, 0, role);
        t.setText(s);
        return t;
    }
}
