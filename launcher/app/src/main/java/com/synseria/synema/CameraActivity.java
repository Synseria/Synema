package com.synseria.synema;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.webrtc.SurfaceViewRenderer;

/**
 * The cameras full screen and live, through Home Assistant: the dashboard's last still at once, then
 * Home Assistant's pictures (CameraStream), then the WebRTC picture and sound (CameraRtc) from its first
 * frame; the pictures stay if the call fails. Left and Right switch between the cameras of home.json
 * "cameras"; Back returns.
 *
 * Talking goes through the camera's speaker. The remote's microphone streams only once its own button
 * is pressed, so that button talks here (RemoteKeyService leaves it to this screen): pressed, the mic
 * opens until pressed again; held, it stays open while held. OK on the talk button does the same.
 */
public class CameraActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Scale.wrap(base));
    }
    /** Which camera to open first, from the dashboard's cameras row. */
    static final String EXTRA_INDEX = "index";
    /** The remote's voice buttons: the Assistant button (F12 once renamed, KeyLayouts) and Search. */
    private static final int[] VOICE_KEYS = {KeyEvent.KEYCODE_F12, KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST};
    /** A voice button held at least this long talks only while held. */
    private static final long HOLD_MS = 600;

    private HomeConfig home;
    private HomeAssistant ha;
    private ImageView frame;
    private SurfaceViewRenderer video;
    private TextView label, caption;
    private View talk, talkButton;
    private VoiceRing ring;
    private CameraStream stream;
    private CameraRtc rtc;
    private int index;
    /** The mic opened with the voice button now down, at this time; -1 otherwise. */
    private long openedByPressAt = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        frame = findViewById(R.id.frame);
        video = findViewById(R.id.video);
        label = findViewById(R.id.label);
        talk = findViewById(R.id.talk);
        talkButton = findViewById(R.id.btnTalk);
        caption = findViewById(R.id.talkCaption);
        ring = new VoiceRing(this, talkButton, getColor(R.color.syn_danger));
        ((FrameLayout) findViewById(R.id.talkRoom)).addView(ring, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        talkButton.setOnClickListener(v -> {
            if (rtc != null) setTalking(!rtc.talking());
        });
        talkButton.setOnFocusChangeListener(Focus::lift);
        home = HomeConfig.load(this);
        ha = HomeAssistant.load(this);
        if (ha == null || home.cameras.isEmpty()) finish();
        index = Math.max(0, Math.min(home.cameras.size() - 1, getIntent().getIntExtra(EXTRA_INDEX, 0)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        RemoteKeyService.voiceKeyHere(true);
        if (ha != null && !home.cameras.isEmpty()) show(index);
    }

    @Override
    protected void onPause() {
        super.onPause();
        RemoteKeyService.voiceKeyHere(false);
        stop();
    }

    private void stop() {
        if (stream != null) stream.stop();
        stream = null;
        if (rtc != null) rtc.release();
        rtc = null;
        talkButton.setActivated(false);
        ring.level(-1);
        openedByPressAt = -1;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int n = home.cameras.size();
        if (n > 1 && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            show((index + (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : n - 1)) % n);
            return true;
        }
        if (isVoiceKey(keyCode)) {
            if (event.getRepeatCount() == 0 && rtc != null && rtc.canTalk()) {
                boolean open = !rtc.talking();
                setTalking(open);
                openedByPressAt = open ? event.getEventTime() : -1;
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (!isVoiceKey(keyCode)) return super.onKeyUp(keyCode, event);
        if (openedByPressAt >= 0 && event.getEventTime() - openedByPressAt >= HOLD_MS && rtc != null && rtc.talking()) {
            setTalking(false);
        }
        openedByPressAt = -1;
        return true;
    }

    private static boolean isVoiceKey(int keyCode) {
        for (int k : VOICE_KEYS) if (k == keyCode) return true;
        return false;
    }

    /** Opens or closes the mic: the button red with the voice round it while open. */
    private void setTalking(boolean on) {
        if (rtc == null) return;
        rtc.talk(on);
        talkButton.setActivated(on);
        ring.level(on ? 0 : -1);
        caption.setText(on ? R.string.camera_talking : R.string.camera_talk);
    }

    private void show(int i) {
        index = i;
        stop();
        HomeConfig.Labelled cam = home.cameras.get(i);
        int n = home.cameras.size();
        label.setText(n > 1 ? "‹  " + cam.label + "  ›   " + (i + 1) + "/" + n : cam.label);
        talk.setVisibility(View.GONE);
        frame.setVisibility(View.VISIBLE);
        // The dashboard's last still at once, while the live pictures connect.
        frame.setImageBitmap(CameraRow.lastFrame(cam.entity));
        stream = new CameraStream(ha, cam.entity, getResources().getDisplayMetrics().widthPixels, frame::setImageBitmap);
        stream.start();
        rtc = new CameraRtc(this, ha, cam.stream, cam.sound, video, new CameraRtc.Listener() {
            @Override
            public void onVideo() {
                if (stream != null) stream.stop();
                stream = null;
                frame.setVisibility(View.GONE);
                caption.setText(R.string.camera_talk);
                talk.setVisibility(View.VISIBLE);
                talkButton.requestFocus();
            }

            @Override
            public void onFailed() {
                // The pictures carry on; only the call is given up.
                if (rtc != null) rtc.release();
                rtc = null;
                talk.setVisibility(View.GONE);
                if (stream == null) show(index);
            }

            @Override
            public void onMicLevel(float level) {
                ring.level(level);
            }
        });
        rtc.start();
    }
}
