package com.synseria.synema;

import android.content.Context;
import android.content.res.Resources;
import android.widget.TextView;

/** The design system's text roles and sizes (styles.xml, jetons.xml) for code that builds its views. */
final class Tokens {
    private Tokens() {
    }

    /** A text in a role (R.style.Role_*), colour and size included. */
    static TextView text(Context ctx, CharSequence s, int role) {
        TextView t = new TextView(ctx, null, 0, role);
        t.setText(s);
        return t;
    }

    /** A size token (R.dimen.syn_typo_*_taille) in sp, for what takes sp. */
    static float sp(Context ctx, int dimen) {
        Resources r = ctx.getResources();
        return r.getDimension(dimen) / (r.getDisplayMetrics().density * r.getConfiguration().fontScale);
    }
}
