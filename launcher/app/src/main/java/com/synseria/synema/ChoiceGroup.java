package com.synseria.synema;

import android.content.Context;
import android.view.KeyEvent;
import android.widget.LinearLayout;

/**
 * A single-choice group (tabs, background swatches): one focusable whole, whose cells are only drawn.
 * Left and Right choose the neighbouring cell at once and never leave the group; Up and Down do. The
 * chosen cell is the cell set activated: it alone is marked, so there is no focus ring on the group.
 */
final class ChoiceGroup extends LinearLayout {
    interface OnChoice {
        void chosen(int index);
    }

    private int chosen;
    private OnChoice listener;

    ChoiceGroup(Context ctx) {
        super(ctx);
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
    }

    void onChoice(OnChoice listener) {
        this.listener = listener;
    }

    int chosenIndex() {
        return chosen;
    }

    /** Marks this cell as the chosen one, without telling the listener. */
    void choose(int index) {
        chosen = index;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).setActivated(i == index);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int step = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? (rtl ? -1 : 1) : keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? (rtl ? 1 : -1) : 0;
        if (step == 0) return super.onKeyDown(keyCode, event);
        int next = chosen + step;
        if (next >= 0 && next < getChildCount()) {
            choose(next);
            if (listener != null) listener.chosen(next);
        }
        return true;
    }
}
