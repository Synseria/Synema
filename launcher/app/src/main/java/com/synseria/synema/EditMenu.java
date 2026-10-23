package com.synseria.synema;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The dashboard's edit menus. Holding OK on a favourite: move it, remove it, close the app, add one
 * (an app or a TV input), or customise the home screen: show or hide its sections, pick the
 * background, applied at once. Choices are saved in the config.json overlay (Config.save), so a pushed
 * theme and an edit on the TV read and write the same keys.
 */
final class EditMenu {

    /** What the dashboard does once a choice is made. */
    interface Host {
        /** Starts moving this favourite with Left and Right. */
        void startMove(String pkg);

        /** The favourites or the sections changed: rebuild. */
        void changed();
    }

    static final String BLOCK_HERO = "hero", BLOCK_RESUME = "resume", BLOCK_PICKS = "picks", BLOCK_HOUSE = "house",
            BLOCK_CAMERAS = "cameras";

    /** The menus read like the TV's own settings: Roboto, crisp at small sizes where Outfit blurs. */
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final Activity activity;
    private final Host host;

    EditMenu(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    void open(String pkg, List<String> favourites) {
        Dialog d = dialog();
        LinearLayout rows = rows(label(pkg));
        row(rows, R.string.edit_move, () -> {
            d.dismiss();
            host.startMove(pkg);
        });
        row(rows, R.string.edit_remove, () -> {
            d.dismiss();
            List<String> next = new ArrayList<>(favourites);
            next.remove(pkg);
            saveFavourites(next);
        });
        if (!pkg.startsWith(Favourites.INPUT)) {
            row(rows, R.string.edit_close_app, () -> {
                d.dismiss();
                closeApp(pkg);
            });
        }
        row(rows, R.string.edit_add, () -> {
            d.dismiss();
            pickApp(favourites);
        });
        row(rows, R.string.customize, () -> {
            d.dismiss();
            customize(favourites);
        });
        show(d, rows);
    }

    /** Holding OK on a title: open its app instead, or hide it from the dashboard's rows. */
    void titleMenu(TvListings.Item item, String appName) {
        Dialog d = dialog();
        LinearLayout rows = rows(item.title);
        rowText(rows, activity.getString(R.string.edit_open_app, appName), () -> {
            d.dismiss();
            android.content.Intent launch = activity.getPackageManager().getLeanbackLaunchIntentForPackage(item.pkg);
            if (launch != null) activity.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        row(rows, R.string.edit_hide_title, () -> {
            d.dismiss();
            List<String> hidden = new ArrayList<>(Config.load(activity).hiddenTitles);
            if (!hidden.contains(item.id())) hidden.add(item.id());
            if (!Config.save(activity, "hidden_titles", hidden)) failed();
            host.changed();
        });
        show(d, rows);
    }

    /** From All apps: make it a favourite or not, hide it, close it. */
    void appMenu(String pkg) {
        List<String> favourites = Config.load(activity).dockApps;
        boolean favourite = favourites.contains(pkg);
        Dialog d = dialog();
        LinearLayout rows = rows(label(pkg));
        row(rows, favourite ? R.string.edit_remove : R.string.edit_make_favourite, () -> {
            d.dismiss();
            List<String> next = new ArrayList<>(favourites);
            if (favourite) next.remove(pkg);
            else next.add(pkg);
            saveFavourites(next);
        });
        row(rows, R.string.edit_hide_app, () -> {
            d.dismiss();
            List<String> hidden = new ArrayList<>(Config.load(activity).hiddenApps);
            if (!hidden.contains(pkg)) hidden.add(pkg);
            if (!Config.save(activity, "hidden_apps", hidden)) failed();
            host.changed();
        });
        row(rows, R.string.edit_close_app, () -> {
            d.dismiss();
            closeApp(pkg);
        });
        show(d, rows);
    }

    /** Customising the home screen: the sections shown, the background, a new favourite. */
    private void customize(List<String> favourites) {
        Dialog d = dialog();
        LinearLayout rows = rows(activity.getString(R.string.customize));
        header(rows, R.string.menu_home);
        sectionRows(rows);
        int hiddenTitles = Config.load(activity).hiddenTitles.size();
        if (hiddenTitles > 0) {
            rowText(rows, activity.getResources().getQuantityString(R.plurals.edit_show_titles, hiddenTitles, hiddenTitles), () -> {
                d.dismiss();
                if (!Config.save(activity, "hidden_titles", new ArrayList<String>())) failed();
                host.changed();
            });
        }
        header(rows, R.string.backdrop);
        backdropSwatches(rows);
        TextView motion = rowText(rows, activity.getString(R.string.backdrop_motion), null);
        Runnable paintMotion = () -> motion.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                toggle(Config.load(activity).backdropMotion), null);
        paintMotion.run();
        motion.setOnClickListener(v -> {
            if (!Config.save(activity, "backdrop_motion", !Config.load(activity).backdropMotion)) failed();
            paintMotion.run();
            host.changed();
        });
        header(rows, R.string.menu_favourites);
        row(rows, R.string.edit_add_favourite, () -> {
            d.dismiss();
            pickApp(favourites);
        });
        // TMDB's terms ask for this credit wherever its data shows.
        if (Tmdb.get(activity) != null) {
            TextView credit = text(activity.getString(R.string.tmdb_credit), 10);
            credit.setTextColor(0x99F2F2F5);
            credit.setSingleLine(false);
            credit.setPadding(dp(14), dp(14), dp(14), dp(4));
            rows.addView(credit);
        }
        show(d, rows);
    }

    /**
     * Ends the app's processes left in the background, which is what a favourite is from Home. A full
     * force-stop needs a system permission no sideloaded launcher can hold.
     */
    private void closeApp(String pkg) {
        android.app.ActivityManager am = activity.getSystemService(android.app.ActivityManager.class);
        if (am != null) am.killBackgroundProcesses(pkg);
        Toast.makeText(activity, activity.getString(R.string.edit_closed, label(pkg)), Toast.LENGTH_SHORT).show();
    }

    /** Every TV app not already a favourite; the chosen one goes at the end of the row. */
    private void pickApp(List<String> favourites) {
        PackageManager pm = activity.getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        List<String[]> choices = new ArrayList<>();
        for (ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (favourites.contains(pkg) || pkg.equals(activity.getPackageName())) continue;
            choices.add(new String[]{String.valueOf(ri.loadLabel(pm)), pkg});
        }
        choices.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
        // The TV's inputs after the apps: a console or an Apple TV opens like an app.
        for (String[] in : Favourites.inputs(activity)) {
            String port = in[0].substring(0, in[0].lastIndexOf('|') + 1);
            boolean already = false;
            for (String f : favourites) already |= f.startsWith(port);
            if (!already) choices.add(new String[]{in[1], in[0]});
        }
        Dialog d = dialog();
        LinearLayout rows = rows(activity.getString(R.string.edit_add));
        for (String[] c : choices) {
            rowText(rows, c[0], () -> {
                d.dismiss();
                List<String> next = new ArrayList<>(favourites);
                next.add(c[1]);
                saveFavourites(next);
            });
        }
        show(d, rows);
    }

    /** Each section with a switch; OK flips it, saves and redraws the dashboard at once. */
    private void sectionRows(LinearLayout rows) {
        String[][] blocks = {{BLOCK_HERO, activity.getString(R.string.edit_hero)},
                {BLOCK_RESUME, activity.getString(R.string.continue_watching)},
                {BLOCK_PICKS, activity.getString(R.string.discover)},
                {BLOCK_CAMERAS, activity.getString(R.string.cameras)},
                {BLOCK_HOUSE, activity.getString(R.string.edit_house_panel)}};
        for (String[] b : blocks) {
            TextView row = rowText(rows, b[1], null);
            Runnable paint = () -> row.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                    toggle(!Config.load(activity).hiddenBlocks.contains(b[0])), null);
            paint.run();
            row.setOnClickListener(v -> {
                List<String> hidden = new ArrayList<>(Config.load(activity).hiddenBlocks);
                if (!hidden.remove(b[0])) hidden.add(b[0]);
                if (!Config.save(activity, "hidden_blocks", hidden)) failed();
                paint.run();
                host.changed();
            });
        }
    }

    /**
     * The backgrounds as swatches: the artwork, each colour theme, and the picture when one is on
     * the TV. OK applies one at once; the chosen one is ringed, the focused one named below.
     */
    private void backdropSwatches(LinearLayout rows) {
        List<String[]> choices = new ArrayList<>();
        choices.add(new String[]{Config.BACKDROP_ARTWORK, activity.getString(R.string.backdrop_artwork)});
        for (Backdrop.Theme t : Backdrop.THEMES) choices.add(new String[]{t.id, activity.getString(t.label)});
        if (Config.load(activity).wallpaper != null) choices.add(new String[]{Config.BACKDROP_PICTURE, activity.getString(R.string.backdrop_picture)});
        LinearLayout strip = new LinearLayout(activity);
        TextView name = text("", 12);
        name.setTextColor(0xFF8D9BB3);
        name.setPadding(dp(4), dp(8), 0, 0);
        List<View> swatches = new ArrayList<>();
        for (String[] c : choices) {
            View sw = new View(activity);
            sw.setFocusable(true);
            sw.setClickable(true);
            sw.setBackground(swatch(c[0]));
            sw.setForeground(activity.getDrawable(R.drawable.dash_focus_ring));
            sw.setOnFocusChangeListener((v, has) -> {
                if (has) name.setText(c[1]);
                else name.post(() -> {
                    if (!strip.hasFocus()) showChosen(name, choices);   // leaving the strip: the chosen one again
                });
            });
            sw.setOnClickListener(v -> {
                if (!Config.save(activity, "backdrop", c[0])) failed();
                for (int i = 0; i < swatches.size(); i++) swatches.get(i).setActivated(choices.get(i)[0].equals(c[0]));
                host.changed();
            });
            sw.setActivated(c[0].equals(Config.load(activity).backdrop));
            swatches.add(sw);
            // Equal shares of the panel's width, however many backgrounds there are.
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            lp.setMarginEnd(dp(6));
            strip.addView(sw, lp);
        }
        rows.addView(strip);
        rows.addView(name);
        showChosen(name, choices);
    }

    private void showChosen(TextView name, List<String[]> choices) {
        String chosen = Config.load(activity).backdrop;
        for (String[] c : choices) if (c[0].equals(chosen)) name.setText(c[1]);
    }

    /** A background's swatch: its colours in miniature, with a white ring once chosen (activated). */
    private Drawable swatch(String id) {
        Backdrop.Theme t = Backdrop.theme(id);
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                t != null ? new int[]{Backdrop.lit(t, 1, 0), t.base, Backdrop.lit(t, 0, 1)}
                        : id.equals(Config.BACKDROP_PICTURE) ? new int[]{0xFF0F3D4A, 0xFF2A1F55}
                        : new int[]{0xFF6B4A3A, 0xFF22303F, 0xFF3A2E5A});
        fill.setCornerRadius(dp(10));
        GradientDrawable chosen = new GradientDrawable();
        chosen.setCornerRadius(dp(10));
        chosen.setStroke(dp(2), 0xFFFFFFFF);
        StateListDrawable ring = new StateListDrawable();
        ring.addState(new int[]{android.R.attr.state_activated}, chosen);
        ring.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        return new LayerDrawable(new Drawable[]{fill, ring});
    }

    /** A switch drawn at a row's end: accent and knob right when on, grey and knob left when off. */
    private Drawable toggle(boolean on) {
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dp(9));
        track.setColor(on ? 0xFF4C8DF6 : 0x40FFFFFF);
        track.setSize(dp(30), dp(18));
        GradientDrawable knob = new GradientDrawable();
        knob.setShape(GradientDrawable.OVAL);
        knob.setColor(0xFFFFFFFF);
        knob.setSize(dp(14), dp(14));
        LayerDrawable l = new LayerDrawable(new Drawable[]{track, knob});
        int m = dp(2);
        l.setLayerInset(1, on ? dp(14) : m, m, on ? m : dp(14), m);
        l.setBounds(0, 0, dp(30), dp(18));
        return l;
    }

    private void saveFavourites(List<String> next) {
        if (!Config.save(activity, "dock_apps", next)) failed();
        host.changed();
    }

    /** Saves the order left by a move. */
    void saveOrder(List<String> order) {
        saveFavourites(order);
    }

    private void failed() {
        Toast.makeText(activity, R.string.edit_not_saved, Toast.LENGTH_SHORT).show();
    }

    private String label(String id) {
        Favourites.Entry fav = Favourites.resolve(activity, id);
        return fav != null ? fav.label.toString() : id;
    }

    // ---- dialog building -------------------------------------------------------------------------

    private Dialog dialog() {
        Dialog d = new Dialog(activity);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return d;
    }

    /** The panel's column: its title, then the rows added after. */
    private LinearLayout rows(String title) {
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(22), dp(26), dp(22), dp(22));
        TextView t = text(title, 20);
        t.setTypeface(MEDIUM);
        t.setPadding(dp(4), 0, 0, dp(6));
        rows.addView(t);
        return rows;
    }

    /** A small grey title above a group of rows. */
    private void header(LinearLayout rows, int label) {
        TextView h = text(activity.getString(label).toUpperCase(java.util.Locale.getDefault()), 10);
        h.setTypeface(MEDIUM);
        h.setLetterSpacing(0.12f);
        h.setTextColor(0xFF8D9BB3);
        h.setPadding(dp(4), dp(18), 0, dp(4));
        rows.addView(h);
    }

    private void row(LinearLayout rows, int label, Runnable action) {
        rowText(rows, activity.getString(label), action);
    }

    private TextView rowText(LinearLayout rows, String label, Runnable action) {
        TextView t = text(label, 14);
        t.setBackgroundResource(R.drawable.menu_row_bg);
        t.setTextColor(activity.getColorStateList(R.color.menu_row_text));
        t.setFocusable(true);
        t.setClickable(true);
        t.setGravity(android.view.Gravity.CENTER_VERTICAL);
        t.setMinHeight(dp(40));
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        if (action != null) t.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        rows.addView(t, lp);
        return t;
    }

    private void show(Dialog d, LinearLayout rows) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(rows);
        scroll.setBackgroundResource(R.drawable.menu_panel_bg);
        d.setContentView(scroll);
        Window w = d.getWindow();
        if (w != null) {
            // A panel along the right edge, the full height, like the TV's own settings.
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(android.view.Gravity.END | android.view.Gravity.FILL_VERTICAL);
            w.setLayout(dp(320), ViewGroup.LayoutParams.MATCH_PARENT);
            w.setDimAmount(0.5f);
        }
        // The menu opens while OK is still held (a long press): its repeats and release must not
        // pick the first row, so OK only counts once it has been released inside the dialog.
        boolean[] armed = {false};
        d.setOnKeyListener((dialog, code, e) -> {
            if (armed[0]) return false;
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            if (!ok || e.getAction() == KeyEvent.ACTION_UP) armed[0] = true;
            return ok;
        });
        d.show();
        rows.requestFocus();   // the first focusable row
    }

    private TextView text(String s, float sp) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(0xFFEEF2F8);
        t.setTypeface(REGULAR);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
