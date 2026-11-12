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

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Scale.wrap(base));
    }

    /** Messages kept in the panel, the oldest leaving first. */
    private static final int MAX_MESSAGES = 6;
    /** The widest a message grows, in dp: a line of a sentence, not of the panel. */
    private static final int BUBBLE_DP = 460;

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
    /** The state line in the transcript (waiting, listening, or a problem), if any. */
    private View status;
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
        String[] names = new String[home.assistantPresets.size()];
        for (int i = 0; i < names.length; i++) names[i] = home.assistantPresets.get(i).name;
        presets.addView(Chips.row(this, names, 0, i -> choose(home.assistantPresets.get(i))));
        presets.setVisibility(names.length > 1 ? View.VISIBLE : View.GONE);
        if (names.length > 0) choose(home.assistantPresets.get(0));
        LinearLayout chips = findViewById(R.id.chips);
        for (String s : home.assistantSuggestions) {
            TextView chip = Chips.button(this, s);
            chip.setOnClickListener(v -> ask(s));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMarginEnd(px(R.dimen.syn_espace_2));
            chips.addView(chip, lp);
        }
        mic().setOnClickListener(v -> listen());
        mic().setOnFocusChangeListener(Focus::lift);
        ring = new VoiceRing(this, mic(), getColor(R.color.syn_encre));
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
            status(getString(R.string.ha_not_configured), null);
        } else if (preset == null) {
            status(getString(R.string.assist_no_answer), null);
        }
        if (canListen()) listen();
        else if (presets.getChildCount() > 0 && presets.getVisibility() == View.VISIBLE) presets.getChildAt(0).requestFocus();
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
            status(getString(R.string.assist_looking), null);
            new Thread(() -> {
                Launch.Target t = Launch.find(this, text, null);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    clearStatus();
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
        status(getString(R.string.assist_thinking), null);
        String sent = conversationId == null && !preset.context.isEmpty() ? context(preset.context) + "\n\n" + text : text;
        // Said once a conversation, but only once it was heard: a retry carries them again.
        boolean withActions = !actionsSent && !actions.isEmpty();
        if (withActions) sent = getString(R.string.assist_actions, actions.context()) + "\n\n" + sent;
        boolean withCatalogue = !catalogueSent && !catalogue.isEmpty() && WATCH.matcher(text).find();
        if (withCatalogue) sent = getString(R.string.assist_catalogue, of(catalogue, text)) + "\n\n" + sent;
        boolean sentActions = withActions, sentCatalogue = withCatalogue;
        ha.converse(sent, preset.agent, home.assistantLanguage, conversationId, reply -> {
            if (isFinishing()) return;
            clearStatus();
            if (reply == null) {
                status(getString(R.string.assist_no_answer), () -> askHomeAssistant(text));
                return;
            }
            actionsSent |= sentActions;
            catalogueSent |= sentCatalogue;
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

    /** Another assistant: a new conversation. */
    private void choose(HomeConfig.Preset p) {
        preset = p;
        conversationId = null;
        catalogueSent = false;
        actionsSent = false;
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

    /** A message: {@code mine} on the right in the accent, else on the left on a recessed grey, its corner by its side squarer. */
    private TextView bubble(String text, boolean mine) {
        TextView t = Tokens.text(this, text, R.style.Role_Corps);
        t.setTextColor(getColor(mine ? R.color.syn_encre_sur_primaire : R.color.syn_encre));
        t.setLineHeight(getResources().getDimensionPixelSize(R.dimen.syn_typo_corps_interligne));
        t.setMaxWidth(dp(BUBBLE_DP));
        t.setPadding(px(R.dimen.syn_espace_3), px(R.dimen.syn_espace_2), px(R.dimen.syn_espace_3), px(R.dimen.syn_espace_2));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(getColor(mine ? R.color.syn_primaire : R.color.syn_creux));
        float r = getResources().getDimension(R.dimen.syn_rayon_carte), tail = getResources().getDimension(R.dimen.syn_rayon_pastille);
        bg.setCornerRadii(mine ? new float[]{r, r, r, r, tail, tail, r, r} : new float[]{r, r, r, r, r, r, tail, tail});
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = mine ? android.view.Gravity.END : android.view.Gravity.START;
        lp.topMargin = px(R.dimen.syn_espace_2);
        add(t, lp);
        return t;
    }

    /** The services that switch something off, close or send home: asked again before they run. */
    private static boolean undoes(String service) {
        return service.endsWith("/turn_off") || service.endsWith("/close_cover") || service.endsWith("/return_to_base");
    }

    /**
     * The agent's actions under its answer, buttons: OK calls the service, the button then saying whether
     * it was done. Never focused by themselves: an OK meant for the mic must not switch the house. An
     * action that switches off, closes or sends home is confirmed first.
     */
    private void cards(List<HouseActions.Action> offered) {
        if (offered.isEmpty() || ha == null) return;
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(px(R.dimen.syn_espace_1), px(R.dimen.syn_espace_1), px(R.dimen.syn_espace_1), px(R.dimen.syn_espace_1));
        for (HouseActions.Action a : offered) {
            TextView card = Chips.button(this, a.label);
            setCardIcon(card, R.drawable.ic_bolt, R.color.syn_encre);
            Runnable run = () -> {
                card.setEnabled(false);
                ha.call(a.service, a.entity, null, ok -> {
                    card.setEnabled(true);
                    if (ok) {
                        card.setText(getString(R.string.assist_done, a.label));
                        card.setTextColor(getColor(R.color.syn_succes));
                        setCardIcon(card, R.drawable.ic_bolt, R.color.syn_succes);
                        card.setClickable(false);
                    } else {
                        card.setText(R.string.ha_unreachable);
                        card.setTextColor(getColor(R.color.syn_danger));
                        setCardIcon(card, R.drawable.ic_warn, R.color.syn_danger);
                    }
                });
            };
            card.setOnClickListener(v -> {
                if (undoes(a.service)) {
                    Confirm.show(this, getString(R.string.confirm_action, a.label), null, R.string.action_confirm, run);
                } else {
                    run.run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(px(R.dimen.syn_espace_2));
            row.addView(card, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(R.dimen.syn_espace_2);
        add(row, lp);
    }

    private void setCardIcon(TextView card, int icon, int tint) {
        card.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        card.setCompoundDrawableTintList(getColorStateList(tint));
        card.setCompoundDrawablePadding(px(R.dimen.syn_espace_1));
    }

    /**
     * The panel's one state line: waiting, listening or a problem ("Name unavailable"), with a retry
     * button when there is something to retry. It replaces the previous one.
     */
    private void status(String text, Runnable retry) {
        clearStatus();
        LinearLayout line = new LinearLayout(this);
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);
        line.setClipChildren(false);
        TextView t = Tokens.text(this, text, R.style.Role_Corps);
        t.setTextColor(getColor(R.color.syn_encre_secondaire));
        line.addView(t);
        if (retry != null) {
            TextView again = Tokens.text(this, getString(R.string.action_retry), R.style.Role_CorpsFort);
            again.setTextColor(getColor(R.color.syn_primaire_encre));
            again.setBackgroundResource(R.drawable.button_tertiary_bg);
            again.setGravity(android.view.Gravity.CENTER);
            again.setMinHeight(px(R.dimen.syn_cible_min));
            again.setPadding(px(R.dimen.syn_espace_4), 0, px(R.dimen.syn_espace_4), 0);
            Focus.button(again);
            again.setOnClickListener(v -> {
                clearStatus();
                retry.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(px(R.dimen.syn_espace_2));
            line.addView(again, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(R.dimen.syn_espace_2);
        add(line, lp);
        status = line;
    }

    private void clearStatus() {
        if (status != null) transcript.removeView(status);
        status = null;
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
        status(getString(R.string.assist_listening), null);
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
            clearStatus();
            ring.level(-1);
            retried = false;
            ArrayList<String> words = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            input.setText("");
            if (words != null && !words.isEmpty()) ask(words.get(0));
        }

        @Override
        public void onError(int error) {
            clearStatus();
            ring.level(-1);
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                status(getString(R.string.assist_not_heard), AssistActivity.this::listen);
            } else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    || error == SpeechRecognizer.ERROR_SERVER) {
                // Google's recognition is now and then not reached at the first try: once more, at once.
                if (!retried) {
                    retried = true;
                    listen();
                    return;
                }
                status(getString(R.string.assist_no_network), AssistActivity.this::listen);
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

    private int px(int dimen) {
        return getResources().getDimensionPixelSize(dimen);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
