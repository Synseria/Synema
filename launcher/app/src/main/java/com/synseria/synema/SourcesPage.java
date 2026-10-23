package com.synseria.synema;

import android.content.Context;
import android.content.Intent;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * The sources, on a page beside the dashboard (SidePage), from the top line's Sources button or the
 * remote's Source button (intent): the TV (its Live TV app), each HDMI port under the name of the device
 * on it, the AirPlay receiver, then the TV's own sources drawer for anything else. Cards on a plate, as
 * the favourites, from the same entries (Favourites): an HDMI port has the same name and card in both.
 * OK switches to the source.
 */
final class SourcesPage {
    static final String KEY = "sources";
    /** In the Home intent: open this page (intent). */
    private static final String EXTRA = "com.synseria.synema.extra.SOURCES";
    /** Philips' Live TV app, the tuner's channels; All apps leaves it out (config.xml) since this page has it. */
    private static final String LIVE_TV = "org.droidtv.playtv";
    /** The TV's own sources drawer (Philips: org.droidtv.channels), the standard input switcher action. */
    private static final String VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    private final MainActivity activity;
    private final SidePage side;
    private final LinearLayout content;
    private final Dock cards;

    SourcesPage(MainActivity activity, SidePage side) {
        this.activity = activity;
        this.side = side;
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        cards = new Dock(activity, null);
        cards.setBackgroundResource(R.drawable.dash_dock_bg);
        cards.setClipChildren(false);
        cards.setClipToPadding(false);
        // The plate reaches past the pictures by a card's room, taken back so they line up with the title.
        int room = Focus.cardRoom(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = activity.dp(18) - room;
        lp.setMarginStart(-room);
        lp.setMarginEnd(-room);
        content.addView(cards, lp);
    }

    /** The Home intent that brings the dashboard with this page open, from any app (a remote button). */
    static Intent intent(Context ctx) {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ctx.getPackageName())
                .putExtra(EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Whether {@code i} asks for this page; it asks once, so a later resume with the same intent does not. */
    static boolean asked(Intent i) {
        boolean asked = i.getBooleanExtra(EXTRA, false);
        i.removeExtra(EXTRA);
        return asked;
    }

    /** Fills the page with the sources there now (a sleeping device leaves its port's name) and shows it. */
    void open(boolean slide) {
        cards.removeAllViews();
        add(Favourites.resolve(activity, LIVE_TV));
        for (String[] input : Favourites.inputs(activity)) add(Favourites.resolve(activity, input[0]));
        add(Favourites.resolve(activity, AirPlayPin.RECEIVER));
        String all = activity.getString(R.string.sources_all);
        Card more = Card.of(activity, Favourites.inputBanner(activity, all), all);
        more.setOnClickListener(v -> activity.open(new Intent(VIEW_INPUTS), v));
        cards.addView(more);
        side.open(KEY, activity.getString(R.string.inputs), content, cards.getChildAt(0),
                () -> activity.findViewById(R.id.btnInputs), slide);
    }

    /**
     * The dashboard has left the screen, for a source or an app: the page goes at once, so that Home
     * shows the dashboard again.
     */
    void left() {
        if (KEY.equals(side.shown())) side.close(false);
    }

    private void add(Favourites.Entry source) {
        if (source == null) return;
        Card card = Card.of(activity, source.banner, source.label);
        card.setOnClickListener(v -> activity.open(source.launch, v));
        cards.addView(card);
    }
}
