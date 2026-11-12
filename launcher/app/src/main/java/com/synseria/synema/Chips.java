package com.synseria.synema;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * Choices with round ends (capsule_button_bg): glass at rest, lighter when chosen, a white ring when
 * focused, growing a little. The assistant's, and the graphs' spans.
 */
final class Chips {
    private Chips() {
    }

    /** A chip: a touch target (syn_cible_min) high, a white ring when focused. */
    static TextView capsule(Context ctx, String text) {
        TextView chip = new TextView(ctx);
        chip.setText(text);
        chip.setTextAppearance(R.style.Role_Corps);
        chip.setSingleLine(true);
        chip.setTextColor(ctx.getColorStateList(R.color.pill_text));
        chip.setBackgroundResource(R.drawable.capsule_button_bg);
        float d = ctx.getResources().getDisplayMetrics().density;
        chip.setPadding(Math.round(16 * d), 0, Math.round(16 * d), 0);
        chip.setMinHeight(ctx.getResources().getDimensionPixelSize(R.dimen.syn_cible_min));
        chip.setGravity(Gravity.CENTER);
        Focus.button(chip);
        return chip;
    }

    @Deprecated
    static TextView capsule(Context ctx, String text, float sp) {
        return capsule(ctx, text);
    }

    /** A row of {@code labels}, {@code chosen} lit; OK on one lights it and tells {@code onChoose} its index. */
    static LinearLayout row(Context ctx, String[] labels, int chosen, IntConsumer onChoose) {
        LinearLayout row = new LinearLayout(ctx);
        row.setClipChildren(false);
        float d = ctx.getResources().getDisplayMetrics().density;
        for (int i = 0; i < labels.length; i++) {
            TextView chip = capsule(ctx, labels[i]);
            chip.setActivated(i == chosen);
            int index = i;
            chip.setOnClickListener(v -> {
                for (int k = 0; k < row.getChildCount(); k++) row.getChildAt(k).setActivated(k == index);
                onChoose.accept(index);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(Math.round(8 * d));
            row.addView(chip, lp);
        }
        return row;
    }

    @Deprecated
    static LinearLayout row(Context ctx, String[] labels, int chosen, int heightDp, IntConsumer onChoose) {
        return row(ctx, labels, chosen, onChoose);
    }
}
