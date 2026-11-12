package com.synseria.synema;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/** The assistant's and the graphs' choices: a single-choice group of cells, and the action buttons beside it. */
final class Chips {
    private Chips() {
    }

    /** A secondary button, a touch target (syn_cible_min) high, a white ring when focused. */
    static TextView button(Context ctx, String text) {
        TextView b = Tokens.line(ctx, text, R.style.Role_CorpsFort);
        b.setTextColor(ctx.getColor(R.color.syn_encre));
        b.setBackgroundResource(R.drawable.button_secondary_bg);
        int pad = ctx.getResources().getDimensionPixelSize(R.dimen.syn_espace_4);
        b.setPadding(pad, 0, pad, 0);
        b.setMinHeight(ctx.getResources().getDimensionPixelSize(R.dimen.syn_cible_min));
        b.setGravity(Gravity.CENTER);
        Focus.button(b);
        return b;
    }

    /** A group of {@code labels}, {@code chosen} marked; Left and Right choose and tell {@code onChoose} the index. */
    static ChoiceGroup row(Context ctx, String[] labels, int chosen, IntConsumer onChoose) {
        ChoiceGroup group = new ChoiceGroup(ctx);
        int gap = ctx.getResources().getDimensionPixelSize(R.dimen.syn_espace_1);
        int pad = ctx.getResources().getDimensionPixelSize(R.dimen.syn_espace_4);
        for (int i = 0; i < labels.length; i++) {
            TextView cell = Tokens.line(ctx, labels[i], R.style.Role_CorpsFort);
            cell.setBackgroundResource(R.drawable.choice_cell_bg);
            cell.setTextColor(ctx.getColorStateList(R.color.choice_cell_text));
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(pad, 0, pad, 0);
            cell.setMinHeight(ctx.getResources().getDimensionPixelSize(R.dimen.syn_cible_min));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(gap);
            group.addView(cell, lp);
        }
        group.choose(chosen);
        group.onChoice(onChoose::accept);
        return group;
    }
}
