package com.synseria.synema;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * The dashboard's cameras row: every camera of home.json as a still refreshed with the panel (OK opens
 * it full screen; the row slides past those beyond its width).
 */
final class CameraRow {
    private static final int LIVE = 0xFFFF5A5A;
    /** Last frames, shown at once when Home comes back. */
    private static final Map<String, Bitmap> frames = new HashMap<>();

    private final MainActivity activity;
    private final TextView label;
    private final CardRow row;
    private final Map<String, ImageView> cameraViews = new HashMap<>();
    private HomeConfig home;
    private HomeAssistant ha;
    private boolean shown;
    private String built = "";

    /** The camera's last still seen on the dashboard, or null. */
    static Bitmap lastFrame(String camera) {
        return frames.get(camera);
    }

    CameraRow(MainActivity activity) {
        this.activity = activity;
        label = activity.findViewById(R.id.camerasLabel);
        row = activity.findViewById(R.id.camerasRow);
    }

    void bind(HomeConfig home, HomeAssistant ha, boolean wanted) {
        this.home = home;
        this.ha = ha;
        shown = ha != null && wanted && !home.cameras.isEmpty();
        MainActivity.section(label, shown);
        if (shown) build();
        refresh();
    }

    /** New frames. */
    void refresh() {
        if (ha == null || !shown) return;
        for (Map.Entry<String, ImageView> e : cameraViews.entrySet()) {
            ImageView view = e.getValue();
            ha.cameraFrame(e.getKey(), activity.dp(150), bmp -> {
                if (bmp == null) return;
                frames.put(e.getKey(), bmp);
                activity.whenStill(() -> view.setImageBitmap(bmp));
            });
        }
    }

    /** A card per camera, live dot and name under it; OK opens it full screen. */
    private void build() {
        StringBuilder sig = new StringBuilder();
        for (HomeConfig.Labelled c : home.cameras) sig.append(c.entity).append(c.label).append(';');
        if (sig.toString().equals(built)) return;
        built = sig.toString();
        row.removeAllViews();
        cameraViews.clear();
        for (int i = 0; i < home.cameras.size(); i++) {
            HomeConfig.Labelled cam = home.cameras.get(i);
            int index = i;
            LinearLayout col = new LinearLayout(activity);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setClipChildren(false);
            Card card = new Card(activity);
            Focus.card(card);
            card.setContentDescription(cam.label);
            ImageView img = new RoundedImageView(activity);
            img.setImageBitmap(frames.get(cam.entity));
            card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            card.setOnClickListener(v -> activity.open(new Intent(activity, CameraActivity.class).putExtra(CameraActivity.EXTRA_INDEX, index), v));
            col.addView(card);
            col.addView(live(cam.label), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.underCard()));
            row.addView(col);
            cameraViews.put(cam.entity, img);
        }
    }

    /** Under a camera, nothing over its picture: a red dot for live, and its name. */
    private TextView live(String label) {
        TextView name = activity.text(label, 8, 0xFFA1A1A6);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(LIVE);
        int size = activity.dp(5);
        dot.setSize(size, size);
        name.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
        name.setCompoundDrawablePadding(activity.dp(5));
        name.setIncludeFontPadding(false);
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setPadding(activity.getResources().getDimensionPixelSize(R.dimen.card_inset), activity.underCardTop(), 0, 0);
        return name;
    }
}
