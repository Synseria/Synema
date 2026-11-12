package com.synseria.synema;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Window;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * The sheet that confirms a destructive gesture: "Verb + object?", the destructive button at the bottom
 * right and Cancel beside it. The focus lands on Cancel, so OK alone never deletes.
 */
final class Confirm {
    private Confirm() {
    }

    /** Asks {@code title}; {@code action} labels the destructive button, whose press runs {@code onConfirm}. */
    static void show(Activity activity, CharSequence title, CharSequence detail, int action, Runnable onConfirm) {
        Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.sheet_confirm);
        ((TextView) d.findViewById(R.id.confirm_title)).setText(title);
        TextView more = d.findViewById(R.id.confirm_detail);
        if (detail != null) {
            more.setText(detail);
            more.setVisibility(android.view.View.VISIBLE);
        }
        TextView go = d.findViewById(R.id.confirm_action);
        go.setText(action);
        go.setOnClickListener(v -> {
            d.dismiss();
            onConfirm.run();
        });
        TextView cancel = d.findViewById(R.id.confirm_cancel);
        cancel.setOnClickListener(v -> d.dismiss());
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(EditMenu.DIM);
        }
        d.show();
        cancel.requestFocus();
    }
}
