package com.synseria.synema;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * Choices with round ends (capsule_button_bg): glass at rest, lighter when chosen, white when focused,
 * growing a little. The assistant's, and the graphs' spans.
 */
final class Chips {
    private Chips() {
    }

    static TextView capsule(Context ctx, String text, float sp) {
        TextView chip = new TextView(ctx);
        chip.setText(text);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        chip.setSingleLine(true);
        chip.setTextColor(ctx.getColorStateList(R.color.pill_text));
        chip.setBackgroundResource(R.drawable.capsule_button_bg);
        float d = ctx.getResources().getDisplayMetrics().density;
        chip.setPadding(Math.round(14 * d), 0, Math.round(14 * d), 0);
        chip.setGravity(Gravity.CENTER);
        chip.setFocusable(true);
        chip.setClickable(true);
        chip.setOnFocusChangeListener((v, has) -> Focus.lift(v, has, Focus.TILE));
        return chip;
    }

    /** A row of {@code labels}, {@code chosen} lit; OK on one lights it and tells {@code onChoose} its index. */
    static LinearLayout row(Context ctx, String[] labels, int chosen, int heightDp, IntConsumer onChoose) {
        LinearLayout row = new LinearLayout(ctx);
        row.setClipChildren(false);
        float d = ctx.getResources().getDisplayMetrics().density;
        for (int i = 0; i < labels.length; i++) {
            TextView chip = capsule(ctx, labels[i], 9.5f);
            chip.setActivated(i == chosen);
            int index = i;
            chip.setOnClickListener(v -> {
                for (int k = 0; k < row.getChildCount(); k++) row.getChildAt(k).setActivated(k == index);
                onChoose.accept(index);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(heightDp * d));
            if (i > 0) lp.setMarginStart(Math.round(6 * d));
            row.addView(chip, lp);
        }
        return row;
    }
}
