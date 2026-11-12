package com.synseria.synema;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The dashboard's edit menus. Holding OK on a favourite: move it, remove it, close the app, add one
 * (an app or a TV input). The settings, from the top line's button: two tabs, Home (sections, hidden
 * apps and titles, favourites) and Theme (the background, applied as it is chosen). Gestures that lose something ask
 * first (Confirm). Choices are saved in the config.json overlay (Config.save), so a pushed theme and
 * an edit on the TV read and write the same keys.
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

    /** The veil behind a panel or a sheet. jeton manquant: syn_opacite_voile */
    static final float DIM = 0.5f;

    /** The panel's width, room for a name over its row. jeton manquant: syn_panneau_largeur */
    private static final int PANEL_DP = 320;

    /** A swatch chosen with the arrows is saved once the arrows rest: each save rebuilds the screens. */
    private static final long SAVE_MS = 400;

    private final Activity activity;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingSave;
    /** Set just before the screen is recreated in a new accent: its menu opens again on the Theme tab. */
    private static boolean reopenOnTheme;

    EditMenu(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        if (reopenOnTheme) {
            reopenOnTheme = false;
            handler.post(() -> customize(1));
        }
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
            removeFavourite(pkg, favourites);
        });
        if (!pkg.startsWith(Favourites.INPUT)) {
            row(rows, R.string.edit_close_app, () -> {
                d.dismiss();
                confirmClose(pkg);
            });
        }
        row(rows, R.string.edit_add, () -> {
            d.dismiss();
            pickApp(favourites);
        });
        show(d, rows);
    }

    /** Holding OK on a title: open its app instead, or hide it from the dashboard's rows. */
    void titleMenu(TvListings.Item item, String appName) {
        Dialog d = dialog();
        LinearLayout rows = rows(item.title);
        row(rows, activity.getString(R.string.edit_open_app, appName), () -> {
            d.dismiss();
            Intent launch = activity.getPackageManager().getLeanbackLaunchIntentForPackage(item.pkg);
            if (launch != null) activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        row(rows, R.string.edit_hide_title, () -> {
            d.dismiss();
            Confirm.show(activity, activity.getString(R.string.confirm_hide_title, item.title), null, R.string.action_hide, () -> {
                List<String> hidden = new ArrayList<>(Config.load(activity).hiddenTitles);
                if (!hidden.contains(item.id())) hidden.add(item.id());
                if (!Config.save(activity, "hidden_titles", hidden)) failed();
                host.changed();
            });
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
            if (favourite) {
                removeFavourite(pkg, favourites);
            } else {
                List<String> next = new ArrayList<>(favourites);
                next.add(pkg);
                saveFavourites(next);
            }
        });
        row(rows, R.string.edit_hide_app, () -> {
            d.dismiss();
            Confirm.show(activity, activity.getString(R.string.confirm_hide_app, label(pkg)), null, R.string.action_hide, () -> {
                List<String> hidden = new ArrayList<>(Config.load(activity).hiddenApps);
                if (!hidden.contains(pkg)) hidden.add(pkg);
                if (!Config.save(activity, "hidden_apps", hidden)) failed();
                host.changed();
            });
        });
        row(rows, R.string.edit_close_app, () -> {
            d.dismiss();
            confirmClose(pkg);
        });
        show(d, rows);
    }

    private void removeFavourite(String pkg, List<String> favourites) {
        Confirm.show(activity, activity.getString(R.string.confirm_remove_favourite, label(pkg)), null, R.string.action_remove, () -> {
            List<String> next = new ArrayList<>(favourites);
            next.remove(pkg);
            saveFavourites(next);
        });
    }

    private void confirmClose(String pkg) {
        Confirm.show(activity, activity.getString(R.string.confirm_close_app, label(pkg)), null, R.string.action_close, () -> closeApp(pkg));
    }

    /** The settings: two tabs, Home and Theme; the left and right arrows on the tabs switch them. */
    void customize() {
        customize(0);
    }

    /** The settings menu on its tab {@code tab}: 0 Home, 1 Theme. */
    private void customize(int tab) {
        List<String> favourites = Config.load(activity).dockApps;
        Dialog d = dialog();
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(R.dimen.syn_espace_6), Scale.marginY(activity), px(R.dimen.syn_espace_6), Scale.marginY(activity));
        panel.addView(titleView(activity.getString(R.string.customize)));

        ChoiceGroup tabs = new ChoiceGroup(activity);
        int[] names = {R.string.tab_home, R.string.tab_theme};
        for (int name : names) {
            TextView cell = text(activity.getString(name), R.style.Role_CorpsFort);
            cell.setBackgroundResource(R.drawable.choice_cell_bg);
            cell.setTextColor(activity.getColorStateList(R.color.choice_cell_text));
            cell.setGravity(Gravity.CENTER);
            cell.setMinHeight(px(R.dimen.syn_cible_min));
            tabs.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabsLp.bottomMargin = px(R.dimen.syn_espace_2);
        panel.addView(tabs, tabsLp);

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Runnable fill = () -> {
            content.removeAllViews();
            if (tabs.chosenIndex() == 0) homeTab(content, d, favourites);
            else themeTab(content, d);
            scroll.scrollTo(0, 0);
        };
        tabs.onChoice(i -> fill.run());
        tabs.choose(tab);
        fill.run();
        d.setOnDismissListener(x -> flushPending());
        showPanel(d, panel, tabs);
    }

    private void homeTab(LinearLayout rows, Dialog d, List<String> favourites) {
        sectionRows(rows);
        Config cfg = Config.load(activity);
        line(rows, activity.getString(R.string.hidden_apps), cfg.hiddenApps.isEmpty() ? null : value(String.valueOf(cfg.hiddenApps.size())), () -> {
            d.dismiss();
            hiddenApps();
        });
        int hiddenTitles = cfg.hiddenTitles.size();
        if (hiddenTitles > 0) {
            row(rows, activity.getResources().getQuantityString(R.plurals.edit_show_titles, hiddenTitles, hiddenTitles), () -> {
                d.dismiss();
                if (!Config.save(activity, "hidden_titles", new ArrayList<String>())) failed();
                host.changed();
            });
        }
        header(rows, R.string.menu_favourites);
        row(rows, R.string.edit_add_favourite, () -> {
            d.dismiss();
            pickApp(favourites);
        });
        // TMDB's terms ask for this credit wherever its data shows.
        if (Tmdb.get(activity) != null) {
            TextView credit = text(activity.getString(R.string.tmdb_credit), R.style.Role_Note);
            credit.setPadding(px(R.dimen.syn_espace_3), px(R.dimen.syn_espace_4), px(R.dimen.syn_espace_3), px(R.dimen.syn_espace_1));
            rows.addView(credit);
        }
    }

    private void themeTab(LinearLayout rows, Dialog menu) {
        colourSwatches(rows, menu);
        backdropSwatches(rows);
        ImageView knob = new ImageView(activity);
        Runnable paint = () -> knob.setImageDrawable(toggle(Config.load(activity).backdropMotion));
        paint.run();
        line(rows, activity.getString(R.string.backdrop_motion), knob, () -> {
            if (!Config.save(activity, "backdrop_motion", !Config.load(activity).backdropMotion)) failed();
            paint.run();
            host.changed();
        });
    }

    /** The apps hidden from All apps, each with a way back. */
    private void hiddenApps() {
        Dialog d = dialog();
        LinearLayout rows = rows(activity.getString(R.string.hidden_apps));
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        rows.addView(list);
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            list.removeAllViews();
            List<String> hidden = Config.load(activity).hiddenApps;
            if (hidden.isEmpty()) {
                TextView none = text(activity.getString(R.string.hidden_apps_none), R.style.Role_Corps);
                none.setTextColor(activity.getColor(R.color.syn_encre_secondaire));
                none.setPadding(px(R.dimen.syn_espace_3), px(R.dimen.syn_espace_2), px(R.dimen.syn_espace_3), px(R.dimen.syn_espace_2));
                list.addView(none);
                return;
            }
            for (String pkg : hidden) {
                TextView show = value(activity.getString(R.string.action_show));
                show.setTextColor(Tokens.primaryInk(activity));
                line(list, label(pkg), show, () -> {
                    List<String> next = new ArrayList<>(Config.load(activity).hiddenApps);
                    next.remove(pkg);
                    if (!Config.save(activity, "hidden_apps", next)) failed();
                    host.changed();
                    fill[0].run();
                    list.requestFocus();
                });
            }
        };
        fill[0].run();
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
            row(rows, c[0], () -> {
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
            ImageView knob = new ImageView(activity);
            Runnable paint = () -> knob.setImageDrawable(toggle(!Config.load(activity).hiddenBlocks.contains(b[0])));
            paint.run();
            line(rows, b[1], knob, () -> {
                List<String> hidden = new ArrayList<>(Config.load(activity).hiddenBlocks);
                if (!hidden.remove(b[0])) hidden.add(b[0]);
                if (!Config.save(activity, "hidden_blocks", hidden)) failed();
                paint.run();
                host.changed();
            });
        }
    }

    /** The accent: one swatch per design-system preset. Choosing it restarts the open screens in the new colour. */
    private void colourSwatches(LinearLayout rows, Dialog menu) {
        List<String[]> choices = new ArrayList<>();
        for (Tokens.Colour c : Tokens.COLOURS) choices.add(new String[]{c.id, activity.getString(c.label)});
        swatchRow(rows, activity.getString(R.string.colour), choices, Config.load(activity).colour,
                this::colourSwatch, id -> {
                    if (Config.load(activity).colour.equals(id)) return;
                    if (!Config.save(activity, "colour", id)) failed();
                    // The menu closes before the screen is rebuilt, and the new screen reopens it on this tab.
                    reopenOnTheme = true;
                    menu.dismiss();
                    activity.recreate();
                });
    }

    /**
     * The backgrounds as one single-choice group of swatches: the artwork ("Auto"), each colour theme,
     * and the picture when one is on the TV.
     */
    private void backdropSwatches(LinearLayout rows) {
        List<String[]> choices = new ArrayList<>();
        choices.add(new String[]{Config.BACKDROP_ARTWORK, activity.getString(R.string.backdrop_artwork)});
        for (Backdrop.Theme t : Backdrop.themes(activity)) choices.add(new String[]{t.id, activity.getString(t.label)});
        if (Config.load(activity).wallpaper != null) choices.add(new String[]{Config.BACKDROP_PICTURE, activity.getString(R.string.backdrop_picture)});
        swatchRow(rows, activity.getString(R.string.backdrop), choices, Config.load(activity).backdrop,
                this::swatch, id -> {
                    if (!Config.save(activity, "backdrop", id)) failed();
                    host.changed();
                });
    }

    /**
     * A label with the chosen name at its end over a single-choice group of swatches: the arrows choose, the
     * chosen swatch is outlined while the group has the focus (swatchCell), and the save waits for the arrows to rest.
     */
    private void swatchRow(LinearLayout rows, String label, List<String[]> choices, String current,
                           java.util.function.Function<String, Drawable> look, java.util.function.Consumer<String> save) {
        int chosen = 0;
        for (int i = 0; i < choices.size(); i++) if (choices.get(i)[0].equals(current)) chosen = i;

        LinearLayout caption = new LinearLayout(activity);
        caption.setGravity(Gravity.CENTER_VERTICAL);
        caption.setPadding(px(R.dimen.syn_espace_3), px(R.dimen.syn_espace_2), px(R.dimen.syn_espace_3), px(R.dimen.syn_espace_2));
        TextView title = text(label, R.style.Role_Corps);
        TextView name = value(choices.get(chosen)[1]);
        caption.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        caption.addView(name);
        rows.addView(caption);

        ChoiceGroup strip = new ChoiceGroup(activity);
        for (int i = 0; i < choices.size(); i++) {
            View sw = new View(activity);
            sw.setBackground(look.apply(choices.get(i)[0]));
            // Equal shares of the panel's width, however many choices there are.
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(R.dimen.syn_rangee_palette), 1f);
            if (i < choices.size() - 1) lp.setMarginEnd(px(R.dimen.syn_espace_2));
            strip.addView(sw, lp);
        }
        strip.choose(chosen);
        strip.onChoice(i -> {
            name.setText(choices.get(i)[1]);
            // Each arrow restarts the wait: only the choice the arrows rest on is saved.
            if (pendingSave != null) handler.removeCallbacks(pendingSave);
            pendingSave = () -> {
                pendingSave = null;
                save.accept(choices.get(i)[0]);
            };
            handler.postDelayed(pendingSave, SAVE_MS);
        });
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stripLp.setMargins(px(R.dimen.syn_espace_1), 0, px(R.dimen.syn_espace_1), px(R.dimen.syn_espace_2));
        rows.addView(strip, stripLp);
    }

    /** Runs the swatch choice's save now if it is still waiting for the arrows to rest (the menu closes). */
    private void flushPending() {
        if (pendingSave == null) return;
        handler.removeCallbacks(pendingSave);
        Runnable save = pendingSave;
        pendingSave = null;
        save.run();
    }

    /** An accent's swatch: its colour. */
    private Drawable colourSwatch(String id) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Tokens.preset(activity, Tokens.colour(id)));
        return swatchCell(fill);
    }

    /** A background's swatch: its colours in miniature. */
    private Drawable swatch(String id) {
        Backdrop.Theme t = Backdrop.theme(activity, id);
        return swatchCell(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                t != null ? new int[]{Backdrop.lit(t, 1, 0), t.base, Backdrop.lit(t, 0, 1)}
                        : id.equals(Config.BACKDROP_PICTURE) ? new int[]{0xFF0F3D4A, 0xFF2A1F55} // off-palette: hints of a photo
                        : new int[]{0xFF6B4A3A, 0xFF22303F, 0xFF3A2E5A})); // off-palette: hints of a photo
    }

    /**
     * A swatch's cell, as a ChoiceGroup cell: the chosen one outlined in the accent only while its group has the
     * focus (activated and selected); without the focus, the name at the row's end says the choice. The swatch
     * always sits inset, the outline at the cell's edge: a swatch in the accent itself keeps its outline apart,
     * and the cell is the same size in every state.
     */
    private Drawable swatchCell(GradientDrawable fill) {
        float radius = activity.getResources().getDimension(R.dimen.syn_rayon_controle);
        int inset = px(R.dimen.syn_espace_1);
        fill.setCornerRadius(Math.max(0, radius - inset));   // concentric with the outline
        GradientDrawable outline = new GradientDrawable();
        outline.setCornerRadius(radius);
        outline.setStroke(px(R.dimen.syn_trait_etat), Tokens.primary(activity));
        StateListDrawable ring = new StateListDrawable();
        ring.addState(new int[]{android.R.attr.state_activated, android.R.attr.state_selected}, outline);
        ring.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        LayerDrawable cell = new LayerDrawable(new Drawable[]{fill, ring});
        cell.setLayerInset(0, inset, inset, inset, inset);
        return cell;
    }

    /** A switch drawn at a row's end: accent and knob right when on, grey and knob left when off. */
    private Drawable toggle(boolean on) {
        int width = px(R.dimen.syn_interrupteur_largeur), height = px(R.dimen.syn_interrupteur_hauteur), knobSize = px(R.dimen.syn_case);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(height / 2f);
        track.setColor(on ? Tokens.primary(activity) : activity.getColor(R.color.syn_filet));
        track.setSize(width, height);
        GradientDrawable knob = new GradientDrawable();
        knob.setShape(GradientDrawable.OVAL);
        knob.setColor(activity.getColor(R.color.syn_encre));
        knob.setSize(knobSize, knobSize);
        LayerDrawable l = new LayerDrawable(new Drawable[]{track, knob});
        int m = (height - knobSize) / 2;
        l.setLayerInset(1, on ? width - knobSize - m : m, m, on ? m : width - knobSize - m, m);
        l.setBounds(0, 0, width, height);
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
        rows.setPadding(px(R.dimen.syn_espace_6), Scale.marginY(activity), px(R.dimen.syn_espace_6), Scale.marginY(activity));
        rows.addView(titleView(title));
        return rows;
    }

    private TextView titleView(String title) {
        TextView t = text(title, R.style.Role_Titre);
        t.setPadding(px(R.dimen.syn_espace_1), 0, 0, px(R.dimen.syn_espace_2));
        return t;
    }

    /** A quiet title above a group of rows. */
    private void header(LinearLayout rows, int label) {
        TextView h = text(activity.getString(label), R.style.Role_Note);
        h.setPadding(px(R.dimen.syn_espace_3), px(R.dimen.syn_espace_4), 0, px(R.dimen.syn_espace_1));
        rows.addView(h);
    }

    private void row(LinearLayout rows, int label, Runnable action) {
        line(rows, activity.getString(label), null, action);
    }

    private void row(LinearLayout rows, String label, Runnable action) {
        line(rows, label, null, action);
    }

    /** A value shown at a row's end, quieter than its label. */
    private TextView value(String s) {
        TextView v = text(s, R.style.Role_Corps);
        v.setTextColor(activity.getColor(R.color.syn_encre_secondaire));
        return v;
    }

    /** A row: its label at the left, its value or control (if any) at the right; OK runs the action. */
    private LinearLayout line(LinearLayout rows, String label, View end, Runnable action) {
        LinearLayout line = new LinearLayout(activity);
        line.setBackgroundResource(R.drawable.menu_row_bg);
        line.setFocusable(true);
        line.setClickable(true);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(px(R.dimen.syn_cible_min));
        line.setPadding(px(R.dimen.syn_espace_3), px(R.dimen.syn_espace_2), px(R.dimen.syn_espace_3), px(R.dimen.syn_espace_2));
        line.addView(text(label, R.style.Role_Corps), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (end != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(px(R.dimen.syn_espace_2));
            line.addView(end, lp);
        }
        line.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(R.dimen.syn_espace_1);
        rows.addView(line, lp);
        return line;
    }

    private void show(Dialog d, LinearLayout rows) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(rows);
        showPanel(d, scroll, rows);
    }

    private void showPanel(Dialog d, ViewGroup panel, View focus) {
        panel.setBackgroundResource(R.drawable.menu_panel_bg);
        d.setContentView(panel);
        Window w = d.getWindow();
        if (w != null) {
            // A panel along the right edge, the full height, like the TV's own settings.
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.END | Gravity.FILL_VERTICAL);
            w.setLayout(Math.round(PANEL_DP * activity.getResources().getDisplayMetrics().density), ViewGroup.LayoutParams.MATCH_PARENT);
            w.setDimAmount(DIM);
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
        focus.requestFocus();   // the first focusable row
    }

    private TextView text(String s, int role) {
        return Tokens.text(activity, s, role);
    }

    private int px(int dimen) {
        return activity.getResources().getDimensionPixelSize(dimen);
    }
}
