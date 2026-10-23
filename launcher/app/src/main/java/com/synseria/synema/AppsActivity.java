package com.synseria.synema;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Every TV app, in the dashboard's style: a grid of banners on the dashboard's colour, the focused one
 * lifted with its name. Holding OK opens EditMenu for that app (favourite, hide, close). Built for the
 * TV's GPU like the dashboard: no clipping, no shadows, no full-screen effects. Back returns home.
 */
public class AppsActivity extends Activity implements EditMenu.Host {

    private static final int COLUMNS = 6;

    private final List<App> apps = new ArrayList<>();
    private RecyclerView grid;
    private EditMenu editMenu;
    private float density;

    private static final class App {
        final String pkg;
        final String label;
        final Intent launch;
        final Drawable banner;

        App(String pkg, String label, Intent launch, Drawable banner) {
            this.pkg = pkg;
            this.label = label;
            this.launch = launch;
            this.banner = banner;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);
        // Its plain colour comes from a surface under the window, so the GPU composes it (MovingBackdrop).
        MovingBackdrop.under(this, Backdrop.NAVY, false);
        density = getResources().getDisplayMetrics().density;
        editMenu = new EditMenu(this, this);
        grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setItemAnimator(null);
        grid.setAdapter(new Adapter());
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    /** Every app with a TV entry point, favourites first in their order, then by name; hidden apps left out. */
    private void load() {
        Config cfg = Config.load(this);
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        List<App> favourites = new ArrayList<>(), others = new ArrayList<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || cfg.hiddenApps.contains(pkg)) continue;
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            App app = new App(pkg, String.valueOf(ri.loadLabel(pm)), launch, Favourites.banner(pm, ri.activityInfo));
            (cfg.dockApps.contains(pkg) ? favourites : others).add(app);
        }
        favourites.sort((a, b) -> cfg.dockApps.indexOf(a.pkg) - cfg.dockApps.indexOf(b.pkg));
        others.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        apps.clear();
        apps.addAll(favourites);
        apps.addAll(others);
        grid.getAdapter().notifyDataSetChanged();
        grid.post(() -> {
            if (grid.getFocusedChild() == null && grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
        });
    }

    // ---- EditMenu.Host ---------------------------------------------------------------------------

    @Override
    public void startMove(String pkg) {
        // Favourites are moved on the dashboard, where their row is.
        finish();
    }

    @Override
    public void changed() {
        load();
    }

    // ---- grid ------------------------------------------------------------------------------------

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(parent);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            h.bind(apps.get(position));
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout card;
        final RoundedImageView banner;
        final TextView fallback, name;
        App app;

        Holder(ViewGroup parent) {
            super(new LinearLayout(parent.getContext()));
            LinearLayout col = (LinearLayout) itemView;
            col.setOrientation(LinearLayout.VERTICAL);
            // The lifted card grows past the column's padding: clipped to it, it would lose its ring
            // and the picture's top corners, and look square. Two columns' padding keep the pictures at
            // least the room a focused card needs apart (Focus).
            col.setClipChildren(false);
            col.setClipToPadding(false);
            int inset = getResources().getDimensionPixelSize(R.dimen.card_inset);
            int pad = Math.max(dp(3), (Focus.cardRoom(parent.getContext()) + 1) / 2 - inset);
            col.setPadding(pad, pad, pad, dp(1));
            col.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // The dashboard's card: the picture inset in the frame, the ring on the frame's edge.
            card = new FrameLayout(parent.getContext());
            card.setPadding(inset, inset, inset, inset);
            Focus.card(card);
            banner = new RoundedImageView(parent.getContext());
            card.addView(banner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fallback = label(12);
            fallback.setGravity(Gravity.CENTER);
            card.addView(fallback, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            col.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70) + 2 * inset));
            name = label(10);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            name.setPadding(0, dp(5), 0, 0);
            name.setAlpha(0f);
            col.addView(name);

            card.setOnFocusChangeListener((v, has) -> {
                Focus.lift(v, has, Focus.CARD);
                name.animate().alpha(has ? 1f : 0f).setDuration(140).start();
            });
            card.setOnClickListener(v -> Launch.start(AppsActivity.this, app.launch, v));
            card.setOnLongClickListener(v -> {
                editMenu.appMenu(app.pkg);
                return true;
            });
        }

        void bind(App a) {
            app = a;
            banner.setImageDrawable(a.banner);
            fallback.setText(a.banner == null ? a.label : "");
            name.setText(a.label);
            card.setContentDescription(a.label);
        }
    }

    private TextView label(float sp) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(0xFFEEF2F8);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
