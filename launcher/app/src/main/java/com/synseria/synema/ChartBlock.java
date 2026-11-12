package com.synseria.synema;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;

/**
 * A Chart and, when its statistics could not be read, the tertiary button that tries again: the block
 * says its state whole (Chart draws the words), the button is a real view so the remote can reach it.
 */
final class ChartBlock extends FrameLayout {
    final Chart chart;
    private final TextView retry;

    ChartBlock(Context ctx) {
        super(ctx);
        setClipChildren(false);
        chart = new Chart(ctx);
        addView(chart, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        retry = Tokens.text(ctx, ctx.getString(R.string.action_retry), R.style.Role_CorpsFort);
        retry.setTextColor(ctx.getColor(R.color.syn_primaire_encre));
        retry.setBackgroundResource(R.drawable.button_tertiary_bg);
        retry.setGravity(Gravity.CENTER);
        retry.setMinHeight(retryHeight(ctx));
        int pad = ctx.getResources().getDimensionPixelSize(R.dimen.syn_espace_4);
        retry.setPadding(pad, 0, pad, 0);
        Focus.button(retry);
        retry.setVisibility(View.GONE);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        lp.bottomMargin = ctx.getResources().getDimensionPixelSize(R.dimen.syn_espace_2);
        addView(retry, lp);
    }

    /** The retry button's height, which Chart leaves room for. */
    static int retryHeight(Context ctx) {
        return ctx.getResources().getDimensionPixelSize(R.dimen.syn_cible_min);
    }

    void title(String t) {
        chart.title(t);
    }

    void reading() {
        retry.setVisibility(View.GONE);
        chart.reading();
    }

    void lines(Stats.Span span, List<Chart.Series> s) {
        retry.setVisibility(View.GONE);
        chart.lines(span, s);
    }

    void bars(Stats.Range range, List<Chart.Series> s, String summary) {
        retry.setVisibility(View.GONE);
        chart.bars(range, s, summary);
    }

    void empty(String message) {
        retry.setVisibility(View.GONE);
        chart.empty(message);
    }

    /** The statistics are unavailable; the button runs {@code again}. */
    void failed(Runnable again) {
        retry.setOnClickListener(v -> again.run());
        retry.setVisibility(View.VISIBLE);
        chart.failed();
    }
}
