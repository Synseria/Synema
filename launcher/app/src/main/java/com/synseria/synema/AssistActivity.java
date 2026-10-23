package com.synseria.synema;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The house assistant: a sentence, spoken or typed, goes to the chosen preset's Home Assistant
 * conversation agent (home.json "assistant"), which can act on the house and answers in words, shown and
 * read aloud. A preset's context goes ahead of a conversation's first sentence, with what the TV shows. Opens from the
 * dashboard pill or a remote button set to {"assistant": true}; listens at once when speech recognition
 * is available, and again on that button while open. The conversation continues until the panel is closed.
 *
 * Asked for something to watch, the agent gets what the apps publish (TvListings.catalogue) and plays
 * its choice by naming it on a last line, "▶ title | app", which the panel takes off and opens (Launch).
 *
 * The house's things go with a conversation's first sentence (HouseActions): the agent may end its
 * answer with up to three actions, shown as cards under it; OK on one calls Home Assistant directly.
 * The conversation reads as messages: what was said on the right in blue, the answers on the left.
 *
 * The voice comes from the remote's mic, which streams only once its own button is pressed: from the
 * screen's mic, the remote may be asleep, and nothing is heard (said so, with what to press).
 */
public class AssistActivity extends Activity {

    /** Messages kept in the panel, the oldest leaving first. */
    private static final int MAX_MESSAGES = 6;
    private static final int MINE = 0xFF0A84FF, THEIRS = 0xFF2C2C2E;

    private HomeConfig home;
    private HomeAssistant ha;
    private LinearLayout transcript;
    private ScrollView scroll;
    private EditText input;
    private SpeechRecognizer recognizer;
    /** Speech is mastered louder than films and shows at the same TV volume: read it quieter. */
    private static final float VOICE_VOLUME = 0.35f;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String conversationId;
    private HomeConfig.Preset preset;
    /** What was in front when the assistant opened, for a preset's {app}. */
    private String appShown = "";
    /** The titles to continue, for a preset's {resume}. */
    private volatile String resumeTitles = "";
    private TextView pending;
    /** The halo round the mic while it listens. */
    private VoiceRing ring;
    /** What can be watched, for the agent (catalogue()); read once the panel opens. */
    private volatile String catalogue = "";
    /** The catalogue went with this conversation already. */
    private boolean catalogueSent;
    /** What the agent may offer to do (cards), and whether it went with this conversation. */
    private final HouseActions actions = new HouseActions();
    private boolean actionsSent;
    /** Recognition was not reached once already: the next failure is said. */
    private boolean retried;
    private static final int CATALOGUE_MAX = 60;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_assist);
        home = HomeConfig.load(this);
        ha = HomeAssistant.load(this);
        transcript = findViewById(R.id.transcript);
        scroll = findViewById(R.id.scroll);
        input = findViewById(R.id.input);
        input.setOnEditorActionListener((v, actionId, e) -> {
            boolean enter = e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId != EditorInfo.IME_ACTION_SEND && !enter) return false;
            if (e == null || e.getAction() == KeyEvent.ACTION_DOWN) {
                ask(input.getText().toString());
                input.setText("");
            }
            // Enter's release too: left to the field, it moves the focus on, onto a suggestion or a card,
            // where the next OK would act on the house.
            return true;
        });
        appShown = lastApp();
        if (ha != null) actions.load(ha, home.houseLabel, () -> { });
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            for (TvListings.Item it : TvListings.watchNext(this, 6)) {
                sb.append(sb.length() > 0 ? ", " : "").append(it.title);
            }
            resumeTitles = sb.toString();
            catalogue = catalogue();
        }, "assist-resume").start();
        LinearLayout presets = findViewById(R.id.presets);
        for (HomeConfig.Preset p : home.assistantPresets) {
            TextView chip = Chips.capsule(this, p.name, 11);
            chip.setOnClickListener(v -> choose(p, presets));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMarginEnd(dp(6));
            presets.addView(chip, lp);
        }
        presets.setVisibility(home.assistantPresets.size() > 1 ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!home.assistantPresets.isEmpty()) choose(home.assistantPresets.get(0), presets);
        LinearLayout chips = findViewById(R.id.chips);
        for (String s : home.assistantSuggestions) {
            TextView chip = Chips.capsule(this, s, 11);
            chip.setOnClickListener(v -> ask(s));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMarginEnd(dp(8));
            chips.addView(chip, lp);
        }
        mic().setOnClickListener(v -> listen());
        mic().setOnFocusChangeListener((v, has) -> Focus.lift(v, has, Focus.BUTTON));
        ring = new VoiceRing(this, mic(), 0xFFFFFFFF);
        ((FrameLayout) findViewById(R.id.micRoom)).addView(ring, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (!ttsReady) return;
            tts.setLanguage(Locale.getDefault());
            tts.setSpeechRate(1.05f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                }

                @Override
                public void onDone(String id) {
                    // The agent asked something back: listen for the answer as soon as it is said.
                    if (ANSWER_AND_LISTEN.equals(id)) runOnUiThread(() -> listen());
                }

                @Override
                public void onError(String id) {
                }
            });
        });

        if (ha == null) {
            say(getString(R.string.ha_not_configured), false);
        } else if (preset == null) {
            say(getString(R.string.assist_no_agent), false);
        }
        if (canListen()) listen();
        else if (chips.getChildCount() > 0) chips.getChildAt(0).requestFocus();
        else input.requestFocus();
    }

    /** The remote's assistant button again, the panel open: listen again. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        listen();
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        tts.shutdown();
        super.onDestroy();
    }

    /** "Mets…", "lance…", "regarde…": what the TV plays itself, before asking Home Assistant. */
    private static final java.util.regex.Pattern PLAY = java.util.regex.Pattern.compile(
            "^(mets|met|mettre|lance|lancer|regarde|regarder|joue|jouer|reprends|reprendre|ouvre|ouvrir|démarre|demarre|play|open|watch|start)\\b.*",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    private void ask(String said) {
        String text = said.trim();
        if (text.isEmpty()) return;
        bubble(text, true);
        if (PLAY.matcher(text).matches()) {
            pending = bubble(getString(R.string.assist_looking), false);
            new Thread(() -> {
                Launch.Target t = Launch.find(this, text, null);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    transcript.removeView(pending);
                    if (t == null) {
                        askHomeAssistant(text);
                        return;
                    }
                    bubble(t.title.isEmpty() ? "▶ " + t.app : "▶ " + t.title + " · " + t.app, false);
                    Launch.open(this, t);
                    finish();
                });
            }, "assist-play").start();
            return;
        }
        askHomeAssistant(text);
    }

    /** A request about something to watch: the catalogue goes with it, once a conversation. */
    private static final Pattern WATCH = Pattern.compile(
            "\\b(films?|s[ée]ries?|documentaires?|anim[ée]s?|regarder?|mater|trouve|propose|conseille|lance|mets|envie)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /**
     * The agent's choice to play, "▶ title | app" (the app may be left out): asked for on a last line,
     * a small model also writes it at the end of its sentence.
     */
    private static final Pattern CHOSEN = Pattern.compile("▶[ \\t]*([^|\\n]+?)[ \\t]*(?:\\|[ \\t]*([^\\n]*?)[ \\t]*)?(?=\\n|$)");
    /** A film or a series asked for by name of its kind: the catalogue keeps only that kind (and the unknown). */
    private static final Pattern FILM = Pattern.compile("\\bfilms?\\b", Pattern.CASE_INSENSITIVE),
            SERIES = Pattern.compile("\\bs[ée]ries?\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private void askHomeAssistant(String text) {
        if (ha == null || preset == null) return;
        pending = bubble(getString(R.string.assist_thinking), false);
        String sent = conversationId == null && !preset.context.isEmpty() ? context(preset.context) + "\n\n" + text : text;
        if (!actionsSent && !actions.isEmpty()) {
            sent = getString(R.string.assist_actions, actions.context()) + "\n\n" + sent;
            actionsSent = true;
        }
        if (!catalogueSent && !catalogue.isEmpty() && WATCH.matcher(text).find()) {
            sent = getString(R.string.assist_catalogue, of(catalogue, text)) + "\n\n" + sent;
            catalogueSent = true;
        }
        ha.converse(sent, preset.agent, home.assistantLanguage, conversationId, reply -> {
            if (isFinishing()) return;
            transcript.removeView(pending);
            if (reply == null) {
                say(getString(R.string.assist_no_answer), false);
                return;
            }
            conversationId = reply.conversationId;
            StringBuilder rest = new StringBuilder();
            List<HouseActions.Action> offered = actions.take(reply.speech, rest);
            String speech = plain(rest.toString()).trim();
            // The last mark, should it name more than one.
            int mark = speech.lastIndexOf('▶');
            Matcher chosen = CHOSEN.matcher(speech);
            if (mark >= 0 && chosen.find(mark)) {
                String words = (speech.substring(0, chosen.start()) + speech.substring(chosen.end())).trim();
                play(chosen.group(1), chosen.group(2), words);
                return;
            }
            say(speech, reply.expectsAnswer);
            cards(offered);
        });
    }

    /** Markdown's marks, which a model writes for a screen that shows them as they are: "**21 °C**" is "21 °C". */
    private static final Pattern MARKS = Pattern.compile("\\*\\*|__|`|(?m)^#+\\s*");

    private static String plain(String text) {
        return MARKS.matcher(text).replaceAll("");
    }

    /** Opens what the agent chose, its words shown; said when it is not among what the apps publish. */
    private void play(String title, String app, String words) {
        if (!words.isEmpty()) bubble(words, false);
        new Thread(() -> {
            Launch.Target t = Launch.find(this, title, app);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (t == null) {
                    say(getString(R.string.assist_not_found, title), false);
                    return;
                }
                bubble(t.title.isEmpty() ? "▶ " + t.app : "▶ " + t.title + " · " + t.app, false);
                Launch.open(this, t);
                finish();
            });
        }, "assist-chosen").start();
    }

    /** The catalogue's lines of the kind the request names (a film, a series), those of no known kind too. */
    private String of(String lines, String request) {
        boolean film = FILM.matcher(request).find(), series = SERIES.matcher(request).find();
        if (film == series) return lines;
        String other = " · " + getString(film ? R.string.kind_series : R.string.kind_film);
        StringBuilder sb = new StringBuilder();
        for (String line : lines.split("\n")) {
            if (!line.contains(other + " ") && !line.endsWith(other)) sb.append(line).append('\n');
        }
        return sb.length() == 0 ? lines : sb.toString().trim();
    }

    /** What can be watched, a line each (title, app, kind, facts), for the agent to choose from. Off the main thread. */
    private String catalogue() {
        StringBuilder sb = new StringBuilder();
        Set<String> seen = new HashSet<>();
        PackageManager pm = getPackageManager();
        for (TvListings.Item it : TvListings.catalogue(this)) {
            if (seen.size() >= CATALOGUE_MAX) break;
            if (it.title.isEmpty() || !seen.add(TvListings.plain(it.title))) continue;
            sb.append("- ").append(it.title).append(" | ").append(appLabel(pm, it.pkg));
            int kind = it.type == android.media.tv.TvContract.PreviewPrograms.TYPE_MOVIE ? R.string.kind_film
                    : it.season > 0 || it.episode > 0 || it.type == android.media.tv.TvContract.PreviewPrograms.TYPE_TV_SERIES
                    || it.type == android.media.tv.TvContract.PreviewPrograms.TYPE_TV_SEASON
                    || it.type == android.media.tv.TvContract.PreviewPrograms.TYPE_TV_EPISODE ? R.string.kind_series : 0;
            if (kind != 0) sb.append(" · ").append(getString(kind));
            String facts = it.facts(this);
            if (!facts.isEmpty()) sb.append(" · ").append(facts);
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String appLabel(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Another assistant: a new conversation, the chip lit. */
    private void choose(HomeConfig.Preset p, LinearLayout chips) {
        preset = p;
        conversationId = null;
        catalogueSent = false;
        actionsSent = false;
        for (int i = 0; i < chips.getChildCount(); i++) chips.getChildAt(i).setActivated(home.assistantPresets.get(i) == p);
    }

    /** The preset's context with {app}, {time} and {resume} filled in. */
    private String context(String template) {
        String time = android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date());
        return template.replace("{app}", appShown.isEmpty() ? "l'accueil" : appShown).replace("{time}", time)
                .replace("{resume}", resumeTitles.isEmpty() ? "rien" : resumeTitles);
    }

    /** The app in front before the assistant, by name, from usage stats; empty when unknown or Home. */
    private String lastApp() {
        android.app.usage.UsageStatsManager usm = getSystemService(android.app.usage.UsageStatsManager.class);
        if (usm == null) return "";
        long now = System.currentTimeMillis();
        android.app.usage.UsageEvents events = usm.queryEvents(now - 6 * 3600_000L, now);
        android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
        String last = "";
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED && !e.getPackageName().equals(getPackageName())) {
                last = e.getPackageName();
            }
        }
        if (last.isEmpty()) return "";
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(last, 0)).toString();
        } catch (PackageManager.NameNotFoundException ex) {
            return "";
        }
    }

    private static final String ANSWER = "answer", ANSWER_AND_LISTEN = "answer-listen";

    /** An answer: written in the panel and read aloud; {@code thenListen}: the agent waits for a reply. */
    private void say(String text, boolean thenListen) {
        if (text == null || text.isEmpty()) return;
        bubble(text, false);
        if (!ttsReady) {
            if (thenListen) listen();
            return;
        }
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, VOICE_VOLUME);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, thenListen ? ANSWER_AND_LISTEN : ANSWER);
    }

    /** A message: {@code mine} on the right in blue, else on the left in grey, its corner by its side squarer. */
    private TextView bubble(String text, boolean mine) {
        TextView t = line(text, 13, 0xFFFFFFFF);
        t.setSingleLine(false);
        t.setLineSpacing(dp(2), 1f);
        t.setMaxWidth(dp(460));
        t.setPadding(dp(12), dp(7), dp(12), dp(8));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(mine ? MINE : THEIRS);
        float r = dp(16), tail = dp(4);
        bg.setCornerRadii(mine ? new float[]{r, r, r, r, tail, tail, r, r} : new float[]{r, r, r, r, r, r, tail, tail});
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = mine ? android.view.Gravity.END : android.view.Gravity.START;
        lp.topMargin = dp(6);
        add(t, lp);
        return t;
    }

    /**
     * The agent's actions under its answer: OK calls the service at once, the card then saying whether
     * it was done. Never focused by themselves: an OK meant for the mic must not switch the house.
     */
    private void cards(List<HouseActions.Action> offered) {
        if (offered.isEmpty() || ha == null) return;
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(2), dp(2), dp(2), dp(2));
        for (HouseActions.Action a : offered) {
            TextView card = Chips.capsule(this, a.label, 11);
            card.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_bolt, 0, 0, 0);
            card.setCompoundDrawableTintList(getColorStateList(R.color.pill_text));
            card.setCompoundDrawablePadding(dp(5));
            card.setOnClickListener(v -> {
                card.setEnabled(false);
                ha.call(a.service, a.entity, null, ok -> {
                    card.setText((ok ? "✓  " : "✗  ") + a.label);
                    card.setActivated(ok);
                    card.setEnabled(true);
                    card.setClickable(false);
                });
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
            lp.setMarginEnd(dp(8));
            row.addView(card, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        add(row, lp);
    }

    private void add(View v, LinearLayout.LayoutParams lp) {
        transcript.addView(v, lp);
        while (transcript.getChildCount() > MAX_MESSAGES) transcript.removeViewAt(0);
        // To the end without fullScroll, which also gives the focus to what it scrolls to: a card.
        scroll.post(() -> scroll.scrollTo(0, transcript.getHeight()));
    }

    // ---- speech ----------------------------------------------------------------------------------

    private boolean canListen() {
        return SpeechRecognizer.isRecognitionAvailable(this)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void listen() {
        if (!canListen()) {
            input.requestFocus();
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new Listener());
        }
        if (ttsReady) tts.stop();
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        if (!home.assistantLanguage.isEmpty()) i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, home.assistantLanguage);
        input.setHint(R.string.assist_listening);
        mic().requestFocus();
        ring.level(0);
        recognizer.startListening(i);
    }

    private final class Listener implements RecognitionListener {
        @Override
        public void onPartialResults(Bundle partial) {
            ArrayList<String> words = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (words != null && !words.isEmpty()) input.setText(words.get(0));
        }

        @Override
        public void onResults(Bundle results) {
            input.setHint(R.string.assist_hint);
            ring.level(-1);
            retried = false;
            ArrayList<String> words = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            input.setText("");
            if (words != null && !words.isEmpty()) ask(words.get(0));
        }

        @Override
        public void onError(int error) {
            input.setHint(R.string.assist_hint);
            ring.level(-1);
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                input.setHint(R.string.assist_not_heard);
            } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    || error == SpeechRecognizer.ERROR_SERVER) {
                // Google's recognition is now and then not reached at the first try: once more, at once.
                if (!retried) {
                    retried = true;
                    listen();
                    input.setHint(R.string.assist_again);
                    return;
                }
                input.setHint(R.string.assist_no_network);
            }
        }

        @Override
        public void onReadyForSpeech(Bundle params) {
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        /** The halo round the mic follows the voice: you see it hears you. */
        @Override
        public void onRmsChanged(float rmsdB) {
            ring.level(Math.max(0f, Math.min(1f, (rmsdB - 1f) / 8f)));
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private android.view.View mic() {
        return findViewById(R.id.btnMic);
    }

    private TextView line(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (color != 0) t.setTextColor(color);
        t.setSingleLine(true);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
