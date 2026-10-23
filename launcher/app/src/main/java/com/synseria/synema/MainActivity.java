package com.synseria.synema;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The home screen: favourites on their plate (dock_apps in config.xml, Dock), continue watching and what
 * the apps put forward (TvListings, each a CardRow), and the Home Assistant panel (HousePanel), over a
 * colour tinted by the focused title's artwork (Backdrop).
 * Every app is one press away in AppsActivity; holding OK on a favourite opens EditMenu.
 *
 * Coming back to Home redraws from caches (rows, artwork, house states) and only rows whose content
 * changed are rebuilt, so nothing flashes. While the dashboard is in front, Home Assistant pushes its
 * changes (HousePanel) and the cameras' stills are read every 30 s. Every minute the whole screen glides
 * by a few pixels, against OLED burn-in.
 */
public class MainActivity extends Activity implements EditMenu.Host {

    private static final long REFRESH_MS = 30_000L;
    private static final long SHIFT_MS = 60_000L;
    private static final long PICKS_STALE_MS = 5 * 60_000L;
    /** Titles in a row at most; those beyond the width are reached by sliding it. */
    private static final int ROW_MAX = 15;
    /** A focused favourite's picks read for the band: its first one shown, unless hidden. */
    private static final int PICKS_MAX = 3;
    /**
     * The band follows the focus once it rests, when a row has slid: its new words and artwork are
     * drawn in a frame of their own, not in the slide's.
     */
    private static final long FEATURE_DELAY_MS = 200;
    /** Placeholders while a row is read: more than ever fit, so the row is full whatever its width. */
    private static final int SKELETON_CARDS = 8;

    /** Rows as last shown, kept for the process: Home redraws from them before reading again. Null
     *  until first read: the row shows placeholders meanwhile. */
    private static List<TvListings.Item> resumeCache, discoverCache;
    private static final Map<String, List<TvListings.Item>> picksCache = new HashMap<>();
    private static final Map<String, Long> picksRead = new HashMap<>();

    private float density;
    private Config cfg;
    private HomeConfig home;
    private HomeAssistant ha;
    /** TMDB, to complete what apps publish; null without a token. */
    private Tmdb tmdb;
    private HousePanel house;
    /** The page beside the dashboard (a room, the electricity, the sources), and the sources'. */
    private SidePage side;
    private SourcesPage sources;
    /** Off the screen since the last resume: the sources asked for then are shown at once, not slid in. */
    private boolean away = true;
    private Backdrop backdrop;
    private Dock dock;
    private CardRow resumeRow, discoverRow;
    private CameraRow cameraRow;
    private View hero;
    private FrameLayout heroProgress;
    private ImageView heroArt;
    private TextView heroKicker, heroTitle, heroSub, heroDesc;
    private LinearLayout heroRatings;
    /** The title the featured band shows. */
    private TvListings.Item featured;
    private TextView favLabel, resumeLabel, discoverLabel;
    private View content;
    private EditMenu editMenu;
    /** The favourite being moved with Left and Right, or null outside move mode. */
    private String moving;
    private String favShown = "", resumeShown = "", discoverShown = "";
    /** The focused favourite, whose first pick the band shows. */
    private String picksPkg;
    /** The background's surface under the window, in every mode (MovingBackdrop says why). */
    private MovingBackdrop motion;
    /** The backdrop mode now drawn, so a resume redraws only when the edit menu changed it. */
    private String backdropMode;
    private final Random random = new Random();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            house.refresh();
            cameraRow.refresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };
    private final Runnable shift = new Runnable() {
        @Override
        public void run() {
            handler.postDelayed(this, SHIFT_MS);
            // A side page moves the home away: it shifts again once back.
            if (side.isOpen()) return;
            // A jump of a pixel or two, not a glide: an animation here would redraw the screen for seconds.
            float r = 2 * density;
            content.setTranslationX((random.nextFloat() * 2 - 1) * r);
            content.setTranslationY((random.nextFloat() * 2 - 1) * r);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Started by name (am start -n) rather than as Home, the dashboard would open in a task of its
        // own and stay in memory twice: Home is asked for instead.
        if (!getIntent().hasCategory(Intent.CATEGORY_HOME)) {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        Posters.keepOnDisk(this);
        setContentView(R.layout.activity_home);
        density = getResources().getDisplayMetrics().density;
        content = findViewById(R.id.content);
        dock = findViewById(R.id.dock);
        resumeRow = findViewById(R.id.resumeRow);
        discoverRow = findViewById(R.id.discoverRow);
        favLabel = findViewById(R.id.favLabel);
        resumeLabel = findViewById(R.id.resumeLabel);
        discoverLabel = findViewById(R.id.discoverLabel);
        hero = findViewById(R.id.hero);
        heroArt = findViewById(R.id.heroArt);
        heroKicker = findViewById(R.id.heroKicker);
        heroTitle = findViewById(R.id.heroTitle);
        heroSub = findViewById(R.id.heroSub);
        heroDesc = findViewById(R.id.heroDesc);
        heroRatings = findViewById(R.id.heroRatings);
        heroProgress = findViewById(R.id.heroProgress);
        // The column's padding (room for a focused card, Focus) is taken back, so the band and the
        // pictures start where the top line does.
        int room = Focus.cardRoom(this);
        ((ViewGroup.MarginLayoutParams) findViewById(R.id.main).getLayoutParams()).setMarginStart(-room);
        ViewGroup.MarginLayoutParams band = (ViewGroup.MarginLayoutParams) hero.getLayoutParams();
        band.setMarginStart(room);
        band.setMarginEnd(room);
        cameraRow = new CameraRow(this);
        motion = MovingBackdrop.under(this, Backdrop.NAVY, true);
        backdrop = new Backdrop(motion);
        side = new SidePage(this);
        house = new HousePanel(this, side);
        sources = new SourcesPage(this, side);
        editMenu = new EditMenu(this, this);

        findViewById(R.id.btnAssist).setOnClickListener(v -> open(new Intent(this, AssistActivity.class)));
        findViewById(R.id.btnApps).setOnClickListener(v -> open(new Intent(this, AppsActivity.class)));
        findViewById(R.id.btnInputs).setOnClickListener(v -> sources.open(true));
        findViewById(R.id.btnSettings).setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        TextView caption = findViewById(R.id.topCaption);
        content.getViewTreeObserver().addOnGlobalFocusChangeListener((from, to) -> {
            if (topButton(to) && from != null && !topButton(from)) cameUpFrom = from;
        });
        for (int id : TOP_BUTTONS) {
            View b = findViewById(id);
            b.setOnKeyListener(this::buttonKey);
            // Icons only: the focused one grows (Focus) and says its name under the capsule.
            b.setOnFocusChangeListener((v, has) -> {
                Focus.lift(v, has, Focus.BUTTON);
                if (has) caption.setText(v.getContentDescription());
                else if (!(getCurrentFocus() instanceof ImageView)) caption.setText("");
            });
        }
    }

    /**
     * Down from the buttons goes back where the focus came up from, else to the first favourite, never
     * to whatever lies under them; Up and the ends keep the focus, which the focus finder would
     * otherwise drop somewhere below.
     */
    private boolean buttonKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (cameUpFrom != null && cameUpFrom.isAttachedToWindow() && cameUpFrom.isShown() && cameUpFrom.requestFocus()) {
                    return true;
                }
                List<View> favs = favourites();
                if (favs.isEmpty()) return false;
                favs.get(0).requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return v == firstButton();
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return v.getId() == R.id.btnSettings;
            default:
                return false;
        }
    }

    private static final int[] TOP_BUTTONS = {R.id.btnAssist, R.id.btnApps, R.id.btnInputs, R.id.btnSettings};
    /** What had the focus before it went up to the buttons: Down takes it back there. */
    private View cameUpFrom;

    private static boolean topButton(View v) {
        if (v == null) return false;
        for (int id : TOP_BUTTONS) if (v.getId() == id) return true;
        return false;
    }

    /** The leftmost button: Assistant when a conversation agent is set, else Apps. */
    private View firstButton() {
        View assist = findViewById(R.id.btnAssist);
        return assist.getVisibility() == View.VISIBLE ? assist : findViewById(R.id.btnApps);
    }

    /** Home again, maybe asking for the sources (SourcesPage.intent): onResume reads it. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean askedSources = SourcesPage.asked(getIntent());
        boolean slide = !away;
        away = false;
        Media.stopBehindHome(this);
        // Re-read every time: a pushed theme, home.json or secrets.json applies on the next visit to Home.
        cfg = Config.load(this);
        home = HomeConfig.load(this);
        ha = HomeAssistant.load(this);
        tmdb = Tmdb.get(this);
        moving = null;
        applyBackdrop();
        View focused = getCurrentFocus();
        int focusedFav = Math.max(0, favourites().indexOf(focused));
        buildFavourites();
        house.bind(home, ha, !cfg.hiddenBlocks.contains(EditMenu.BLOCK_HOUSE));
        house.start();
        cameraRow.bind(home, ha, !cfg.hiddenBlocks.contains(EditMenu.BLOCK_CAMERAS));
        showResume(resumeCache);
        showDiscover(discoverCache);
        loadRows();
        if (picksPkg == null && !cfg.dockApps.isEmpty()) picksPkg = cfg.dockApps.get(0);
        loadPicks(picksPkg);
        handler.postDelayed(refresh, REFRESH_MS);
        handler.postDelayed(shift, SHIFT_MS);
        // Back on Home lands where it left (the card or button opened from), else on the favourites.
        // Posted: the window gives first focus to the first focusable view (the top bar) during its
        // first layout, after onResume.
        dock.post(() -> {
            if (askedSources) {
                sources.open(slide);
                return;
            }
            if (focused != null && focused.isAttachedToWindow() && focused.isShown()) {
                focused.requestFocus();
                return;
            }
            List<View> favs = favourites();
            if (!favs.isEmpty()) favs.get(Math.min(focusedFav, favs.size() - 1)).requestFocus();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        house.stop();
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(shift);
        handler.removeCallbacks(picksForFocus);
        handler.removeCallbacks(backdropForFocus);
        if (featureForFocus != null) handler.removeCallbacks(featureForFocus);
        motion.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        away = true;
        sources.left();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) Posters.trim();
    }

    @Override
    public void onBackPressed() {
        if (side.close()) return;
        // Home screen: Back goes back to the top, on the first favourite.
        ((ScrollView) findViewById(R.id.leftScroll)).smoothScrollTo(0, 0);
        List<View> favs = favourites();
        if (!favs.isEmpty()) favs.get(0).requestFocus();
    }

    /** The background chosen in the edit menu: artwork (then set by focus), a colour theme or the picture. */
    private void applyBackdrop() {
        String mode = cfg.backdrop + cfg.backdropMotion + (cfg.wallpaper != null ? cfg.wallpaper.lastModified() : "");
        motion.onResume();
        if (mode.equals(backdropMode)) return;
        backdropMode = mode;
        backdropUri = null;
        motion.setDrift(cfg.backdropMotion);
        handler.removeCallbacks(backdropForFocus);
        switch (cfg.backdrop) {
            case Config.BACKDROP_PICTURE:
                File file = cfg.wallpaper;
                int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
                new Thread(() -> {
                    Bitmap bmp = Wallpaper.decode(file, w, h);
                    runOnUiThread(() -> {
                        if (bmp != null && backdropMode.equals(mode)) backdrop.picture(bmp);
                    });
                }, "wallpaper").start();
                break;
            case Config.BACKDROP_ARTWORK:
                backdrop.clear();
                if (resumeCache != null && !resumeCache.isEmpty()) setBackdrop(resumeCache.get(0));
                break;
            default:
                backdrop.gradient(Backdrop.theme(cfg.backdrop));
        }
    }

    /**
     * The title in the spotlight: the selected card's, on a favourite what that app puts first, else
     * the newest to continue. The band above the rows shows it (artwork, app, title, episode, progress)
     * and the background takes its artwork.
     */
    private void feature(TvListings.Item item, boolean resume) {
        String app = appLabel(getPackageManager(), item.pkg);
        feature(item, getString(resume ? R.string.hero_resume : R.string.hero_discover, app));
    }

    /**
     * On a favourite that publishes nothing (Plex, SmartTube): its own title in progress if there is
     * one, else the app itself, so the band never speaks of another app.
     */
    private void featureApp(String pkg) {
        List<TvListings.Item> resume = visible(resumeCache);
        for (TvListings.Item it : resume == null ? Collections.<TvListings.Item>emptyList() : resume) {
            if (it.pkg.equals(pkg)) {
                feature(it, true);
                return;
            }
        }
        Favourites.Entry fav = Favourites.resolve(this, pkg);
        if (fav == null) return;
        feature(TvListings.Item.app(pkg, fav.label.toString(), fav.launch), getString(R.string.hero_app));
    }

    /**
     * Shows the title in the band, completed from TMDB with what is known of it already; what is not
     * yet known is asked for, and the band completes itself when it comes, in the room kept for it.
     */
    private void feature(TvListings.Item shown, String kicker) {
        Tmdb.Info known = tmdb == null || shown.completed ? null : tmdb.known(shown);
        TvListings.Item item = known != null ? shown.with(known) : shown;
        setBackdrop(item);
        if (cfg.hiddenBlocks.contains(EditMenu.BLOCK_HERO)) return;
        if (featured != null && featured.key().equals(item.key()) && featured.completed == item.completed) return;
        featured = item;
        if (tmdb != null && !item.completed) {
            tmdb.lookup(item, info -> {
                if (info == null || featured == null || featured.completed || !featured.id().equals(item.id())) return;
                whenStill(() -> feature(item.with(info), kicker));
            });
        }
        hero.setVisibility(View.VISIBLE);
        PackageManager pm = getPackageManager();
        Posters.into(this, item.posterUri, dp(200), heroArt, Favourites.bannerOrIcon(pm, item.pkg));
        heroKicker.setText(kicker);
        heroTitle.setText(item.title);
        String facts = item.facts(this);
        // Both lines keep their room when empty: what TMDB adds later never moves the title.
        String sub = item.subtitle + (!item.subtitle.isEmpty() && !facts.isEmpty() ? " · " : "") + facts;
        heroSub.setText(sub);
        heroDesc.setText(item.description);
        showRatings(item);
        heroProgress.removeAllViews();
        heroProgress.setVisibility(item.progress >= 0 ? View.VISIBLE : View.GONE);
        if (item.progress >= 0) heroProgress.addView(progress(item.progress), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * The audience's rating, when known, as a small capsule at the kicker's right: an icon (people) and
     * "72 %", the words for accessibility.
     */
    private void showRatings(TvListings.Item item) {
        heroRatings.removeAllViews();
        if (item.audience < 0) return;
        TextView capsule = text(getString(R.string.rating, item.audience), 9, 0xFFC8D2E3);
        capsule.setIncludeFontPadding(false);
        capsule.setTypeface(heroKicker.getTypeface());
        capsule.setContentDescription(getString(R.string.rating_audience, item.audience));
        capsule.setBackgroundResource(R.drawable.dash_capsule_bg);
        capsule.setPadding(dp(5), dp(1), dp(6), dp(1));
        capsule.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_audience, 0, 0, 0);
        capsule.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(0xFF8D9BB3));
        capsule.setCompoundDrawablePadding(dp(3));
        heroRatings.addView(capsule);
    }

    /**
     * The artwork behind the dashboard: the focused title's large picture (TMDB's) or else its app's
     * artwork, else the newest to continue's; once focus rests (400 ms), so crossing a row fades once.
     * Only in the artwork mode.
     */
    private void setBackdrop(TvListings.Item item) {
        String uri = item.backdropUri != null ? item.backdropUri : item.posterUri;
        if (!Config.BACKDROP_ARTWORK.equals(cfg.backdrop) || uri == null || uri.equals(backdropUri)) return;
        backdropUri = uri;
        handler.removeCallbacks(backdropForFocus);
        handler.postDelayed(backdropForFocus, 400);
    }

    private String backdropUri;
    private final Runnable backdropForFocus = () -> Posters.load(this, backdropUri, Backdrop.SCENE_W, backdrop::show);
    private Runnable featureForFocus;

    /**
     * The focus moved on: the band and the background wait for it to rest, and a background fade under
     * way stops where it is, so nothing redraws the whole screen while a row slides.
     */
    private void focusMoved() {
        if (featureForFocus != null) handler.removeCallbacks(featureForFocus);
        handler.removeCallbacks(backdropForFocus);
        backdropUri = null;
        backdrop.hold();
    }

    // ---- favourites ------------------------------------------------------------------------------

    private void buildFavourites() {
        String signature = TextUtils.join(",", cfg.dockApps) + "|" + moving;
        if (signature.equals(favShown) && dock.getChildCount() > 0) return;
        favShown = signature;
        dock.removeAllViews();
        int n = 0;
        for (String pkg : cfg.dockApps) {
            Favourites.Entry fav = Favourites.resolve(this, pkg);
            if (fav == null) continue;
            Intent launch = fav.launch;
            Card card = Card.of(this, fav.banner, fav.label);
            if (pkg.equals(moving)) card.setForeground(getDrawable(R.drawable.dash_edit_ring));
            card.setTag(pkg);
            card.setOnFocusChangeListener(chain(card.getOnFocusChangeListener(), () -> {
                focusMoved();
                if (moving != null || !fav.isApp()) return;
                picksPkg = pkg;
                handler.removeCallbacks(picksForFocus);
                handler.postDelayed(picksForFocus, 180);   // not on every card crossed on the way
            }));
            card.setOnClickListener(v -> {
                if (moving != null) stopMove();
                else open(launch, v);
            });
            card.setOnLongClickListener(v -> {
                if (moving == null) editMenu.open(pkg, cfg.dockApps);
                return true;
            });
            boolean firstLine = n++ < Dock.PER_LINE;
            card.setOnKeyListener((v, code, e) -> moving != null ? moveKey(code, e) : firstLine && upToButtons(code, e));
            dock.addView(card);
        }
        favLabel.setVisibility(moving != null ? View.VISIBLE : View.GONE);
    }

    /** Up from the first line of favourites always reaches the first button, never the nearer panel. */
    private boolean upToButtons(int code, KeyEvent e) {
        if (code != KeyEvent.KEYCODE_DPAD_UP || e.getAction() != KeyEvent.ACTION_DOWN) return false;
        firstButton().requestFocus();
        return true;
    }

    private final Runnable picksForFocus = () -> loadPicks(picksPkg);

    // ---- continue watching, what the apps put forward, the focused favourite's pick ------------

    private void loadRows() {
        new Thread(() -> {
            List<TvListings.Item> resume = TvListings.watchNext(this, ROW_MAX);
            List<TvListings.Item> discover = TvListings.discover(this, ROW_MAX);
            runOnUiThread(() -> whenStill(() -> {
                resumeCache = resume;
                discoverCache = discover;
                showResume(resume);
                showDiscover(discover);
            }));
        }, "rows").start();
    }

    /** Without the titles hidden from the edit menu; null stays null (not read yet). */
    private List<TvListings.Item> visible(List<TvListings.Item> items) {
        if (items == null || cfg.hiddenTitles.isEmpty()) return items;
        List<TvListings.Item> out = new ArrayList<>();
        for (TvListings.Item it : items) if (!cfg.hiddenTitles.contains(it.id())) out.add(it);
        return out;
    }

    /** {@code items} null: not read yet, placeholders. */
    private void showResume(List<TvListings.Item> all) {
        List<TvListings.Item> items = visible(all);
        boolean shown = (items == null || !items.isEmpty()) && !cfg.hiddenBlocks.contains(EditMenu.BLOCK_RESUME);
        section(resumeLabel, shown);
        String signature = items == null ? SKELETON : signature(items);
        if (signature.equals(resumeShown)) return;
        resumeShown = signature;
        if (items == null) {
            skeleton(resumeRow);
            return;
        }
        if (!items.isEmpty()) feature(items.get(0), true);
        fillRow(resumeRow, items, true);
    }

    /** Every app's picks, interleaved; {@code items} null: not read yet, placeholders. */
    private void showDiscover(List<TvListings.Item> all) {
        List<TvListings.Item> items = visible(all);
        boolean shown = (items == null || !items.isEmpty()) && !cfg.hiddenBlocks.contains(EditMenu.BLOCK_PICKS);
        section(discoverLabel, shown);
        String signature = items == null ? SKELETON : signature(items);
        if (signature.equals(discoverShown)) return;
        discoverShown = signature;
        if (items == null) skeleton(discoverRow);
        else fillRow(discoverRow, items, false);
    }

    /** What the favourite puts first (its preview channel) for the band, from cache then fresh. */
    private void loadPicks(String pkg) {
        if (pkg == null) return;
        featurePick(pkg, picksCache.get(pkg));
        Long read = picksRead.get(pkg);
        if (read != null && SystemClock.elapsedRealtime() - read < PICKS_STALE_MS) return;
        picksRead.put(pkg, SystemClock.elapsedRealtime());
        new Thread(() -> {
            List<TvListings.Item> items = TvListings.picks(this, pkg, PICKS_MAX);
            runOnUiThread(() -> {
                picksCache.put(pkg, items);
                if (pkg.equals(picksPkg)) featurePick(pkg, items);
            });
        }, "picks").start();
    }

    /** On a favourite, the band and the background show what that app puts first. */
    private void featurePick(String pkg, List<TvListings.Item> all) {
        List<TvListings.Item> items = visible(all);
        if (items == null || getCurrentFocus() == null || !favourites().contains(getCurrentFocus())) return;
        if (!items.isEmpty()) feature(items.get(0), false);
        else featureApp(pkg);
    }

    private static final String SKELETON = "…";

    /** A section is its title and row in one bubble: shown or hidden whole. */
    static void section(View label, boolean shown) {
        ((View) label.getParent()).setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    /** Empty cards in the row's shape while it is read: the row appears once, not in jolts. */
    private void skeleton(CardRow row) {
        row.removeAllViews();
        for (int i = 0; i < SKELETON_CARDS; i++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            Card card = new Card(this);
            card.addView(new RoundedImageView(this), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            col.addView(card);
            // Continue watching keeps room for the line under its cards: no jump when they come.
            if (row == resumeRow) col.addView(new View(this), new LinearLayout.LayoutParams(1, underCard()));
            row.addView(col);
        }
    }

    private static String signature(List<TvListings.Item> items) {
        StringBuilder sb = new StringBuilder();
        for (TvListings.Item i : items) sb.append(i.key()).append(';');
        return sb.toString();
    }

    /** 16:9 artwork, the row's width shared out by CardRow; under continue watching's, the app and how far in. */
    private void fillRow(CardRow row, List<TvListings.Item> items, boolean resume) {
        row.removeAllViews();
        PackageManager pm = getPackageManager();
        for (TvListings.Item item : items) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setClipChildren(false);
            Card card = new Card(this);
            Focus.card(card);
            card.setContentDescription(item.title);
            ImageView img = new RoundedImageView(this);
            Posters.into(this, item.posterUri, dp(150), img, Favourites.bannerOrIcon(pm, item.pkg));
            card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            card.setOnFocusChangeListener(chain(card.getOnFocusChangeListener(), () -> {
                focusMoved();
                featureForFocus = () -> feature(item, resume);
                handler.postDelayed(featureForFocus, FEATURE_DELAY_MS);
            }));
            card.setOnClickListener(v -> open(new Intent(item.intent), v));
            card.setOnLongClickListener(v -> {
                editMenu.titleMenu(item, appLabel(getPackageManager(), item.pkg));
                return true;
            });
            col.addView(card);
            if (resume) col.addView(meta(pm, item), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, underCard()));
            row.addView(col);
        }
    }

    private static String appLabel(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    // ---- edit mode -------------------------------------------------------------------------------

    @Override
    public void startMove(String pkg) {
        moving = pkg;
        buildFavourites();
        focusFavourite(pkg);
    }

    @Override
    public void changed() {
        View focused = getCurrentFocus();
        cfg = Config.load(this);
        applyBackdrop();
        buildFavourites();
        house.bind(home, ha, !cfg.hiddenBlocks.contains(EditMenu.BLOCK_HOUSE));
        cameraRow.bind(home, ha, !cfg.hiddenBlocks.contains(EditMenu.BLOCK_CAMERAS));
        if (cfg.hiddenBlocks.contains(EditMenu.BLOCK_HERO)) hero.setVisibility(View.GONE);
        else if (featured != null) hero.setVisibility(View.VISIBLE);
        resumeShown = "";
        showResume(resumeCache);
        discoverShown = "";
        showDiscover(discoverCache);
        if (focused == null || !focused.isAttachedToWindow()) {
            if (!favourites().isEmpty()) favourites().get(0).requestFocus();
        }
    }

    /** Left and Right swap the moving favourite with its neighbour; Back and OK end the move. */
    private boolean moveKey(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) stopMove();
            return true;
        }
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) {
            // Up and Down would leave the row mid-move: keep focus here.
            return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        List<String> order = new ArrayList<>(cfg.dockApps);
        int i = order.indexOf(moving), j = i + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
        if (i < 0 || j < 0 || j >= order.size()) return true;
        Collections.swap(order, i, j);
        cfg = cfg.withDockApps(order);
        buildFavourites();
        focusFavourite(moving);
        return true;
    }

    private void stopMove() {
        String pkg = moving;
        moving = null;
        editMenu.saveOrder(cfg.dockApps);
        focusFavourite(pkg);
    }

    private void focusFavourite(String pkg) {
        for (View v : favourites()) {
            if (pkg != null && pkg.equals(v.getTag())) {
                v.requestFocus();
                return;
            }
        }
    }

    /** The favourite cards, in order. */
    private List<View> favourites() {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < dock.getChildCount(); i++) out.add(dock.getChildAt(i));
        return out;
    }

    // ---- shared with HousePanel ------------------------------------------------------------------

    /** The background's surface. */
    MovingBackdrop motion() {
        return motion;
    }

    /** Runs {@code r} now, or once a side page has finished sliding: an answer must not redraw a slide. */
    void whenStill(Runnable r) {
        side.whenStill(r);
    }

    void runAction(HomeConfig.Action action) {
        // The new state is pushed as Home Assistant learns it (HousePanel).
        Actions.run(this, ha, action, house::pressed);
    }

    /** The line under a card: 9 dp of icon or words, below the room a focused card's ring needs (Focus). */
    int underCard() {
        return underCardTop() + dp(9);
    }

    /** From the card's frame to the line under it: the frame's inset already keeps part of the room. */
    int underCardTop() {
        return Focus.cardRoomBelow(this) - getResources().getDimensionPixelSize(R.dimen.card_inset);
    }

    /**
     * Under a title to continue, nothing over its artwork: the app's icon, then how far in (a bar) or,
     * without a position, the app's name. Aligned with the picture inside the card's frame.
     */
    private LinearLayout meta(PackageManager pm, TvListings.Item item) {
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        int inset = getResources().getDimensionPixelSize(R.dimen.card_inset);
        line.setPadding(inset, underCardTop(), inset, 0);
        ImageView icon = new ImageView(this);
        try {
            icon.setImageDrawable(pm.getApplicationIcon(item.pkg));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        line.addView(icon, new LinearLayout.LayoutParams(dp(9), dp(9)));
        if (item.progress >= 0) {
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(3), 1f);
            plp.setMarginStart(dp(5));
            line.addView(progress(item.progress), plp);
        } else {
            TextView name = text(appLabel(pm, item.pkg), 7, 0xFF8D9BB3);
            name.setPadding(dp(4), 0, 0, 0);
            line.addView(name);
        }
        return line;
    }

    /** How far into a title: a rounded bar on a faint track. */
    LinearLayout progress(float done) {
        LinearLayout track = new LinearLayout(this);
        track.setBackgroundResource(R.drawable.progress_track);
        track.setWeightSum(1f);
        View bar = new View(this);
        bar.setBackgroundResource(R.drawable.progress_bar);
        track.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0.03f, done)));
        return track;
    }

    /** The lift, then something more when focus arrives. */
    private static View.OnFocusChangeListener chain(View.OnFocusChangeListener first, Runnable onFocus) {
        return (v, has) -> {
            first.onFocusChange(v, has);
            if (has) onFocus.run();
        };
    }

    TextView text(CharSequence s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (color != 0) t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    int dp(int v) {
        return Math.round(v * density);
    }

    void open(Intent i) {
        open(i, null);
    }

    /** Opens it; from a card, out of that card (Launch.start). */
    void open(Intent i, View from) {
        Launch.start(this, i, from);
    }
}
