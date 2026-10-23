package com.synseria.synema;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RendererCommon;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.Collections;

/**
 * A camera live over WebRTC, through Home Assistant: the Frigate integration proxies go2rtc's
 * signalling socket ({@code /api/frigate/mse/api/ws?src=<stream>}) behind Home Assistant's sign-in,
 * and the media then flows straight from go2rtc on the LAN (about half a second behind).
 *
 * The picture and the sound are two calls, each on the go2rtc stream that carries it best (home.json
 * cameras' {@code stream} and {@code sound}): a "_sub" stream holds the picture without loss but has no
 * sound, and only the main stream has the speaker's back channel. Talking calls the sound stream again
 * with the TV's microphone; the picture never stops meanwhile.
 */
final class CameraRtc {
    private static final String TAG = "TvHome.Rtc";

    interface Listener {
        /** On the main thread: the picture is showing. */
        void onVideo();

        /** On the main thread: the picture's call could not be set up or dropped. */
        void onFailed();

        /** On the main thread, while talking: the microphone's level, 0 (silence) to 1. */
        void onMicLevel(float level);
    }

    private static boolean initialised;
    private final HomeAssistant ha;
    private final String videoSource, soundSource;
    private final SurfaceViewRenderer view;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final EglBase egl = EglBase.create();
    private PeerConnectionFactory factory;
    private Call video, sound;
    private boolean talking;

    /**
     * @param videoSource the go2rtc stream for the picture, e.g. "doorbell_sub"
     * @param soundSource the one for the sound both ways, e.g. "doorbell"; null for a silent picture
     */
    CameraRtc(Context ctx, HomeAssistant ha, String videoSource, String soundSource, SurfaceViewRenderer view, Listener listener) {
        Context app = ctx.getApplicationContext();
        this.ha = ha;
        this.videoSource = videoSource;
        this.soundSource = soundSource;
        this.view = view;
        this.listener = listener;
        if (!initialised) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions());
            initialised = true;
        }
        view.init(egl.getEglBaseContext(), new RendererCommon.RendererEvents() {
            @Override
            public void onFirstFrameRendered() {
                main.post(listener::onVideo);
            }

            @Override
            public void onFrameResolutionChanged(int w, int h, int rotation) {
            }
        });
        view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        JavaAudioDeviceModule audio = JavaAudioDeviceModule.builder(app)
                // A call's own usage plays on the TV's speakers at the call volume; the door is heard
                // where films are, at their volume (the sound bar over ARC).
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                // The remote's microphone reaches recognition's source; a call's may hear nothing.
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setSamplesReadyCallback(samples -> {
                    float level = level(samples.getData());
                    main.post(() -> {
                        if (talking) listener.onMicLevel(level);
                    });
                })
                .createAudioDeviceModule();
        factory = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setAudioDeviceModule(audio)
                .createPeerConnectionFactory();
        audio.release();   // the factory holds its own reference
    }

    void start() {
        video = new Call(videoSource, false, false);
        if (soundSource != null) sound = new Call(soundSource, true, false);
    }

    boolean canTalk() {
        return soundSource != null;
    }

    boolean talking() {
        return talking;
    }

    /** Talking or listening: the sound's call again, sending the microphone or not. */
    void talk(boolean on) {
        if (soundSource == null || factory == null || on == talking) return;
        talking = on;
        if (sound != null) sound.hangUp();
        sound = new Call(soundSource, true, on);
        Log.i(TAG, on ? "talking" : "listening");
    }

    void release() {
        talking = false;
        if (video != null) video.hangUp();
        if (sound != null) sound.hangUp();
        video = sound = null;
        if (factory != null) factory.dispose();
        factory = null;
        view.release();
        egl.release();
    }

    /** The loudness of 16-bit samples, 0..1, on a scale where a voice near the mic reaches about 1. */
    private static float level(byte[] pcm) {
        long sum = 0;
        int n = pcm.length / 2;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sum += (long) s * s;
        }
        if (n == 0) return 0;
        double db = 10 * Math.log10(Math.max(1, (double) sum / n) / (32768.0 * 32768.0));   // dBFS, -90..0
        return (float) Math.max(0, Math.min(1, (db + 60) / 40));
    }

    /** One call to one go2rtc stream: the picture, or the sound (with the microphone when talking). */
    private final class Call {
        private final String source;
        private final PeerConnection peer;
        private final boolean picture;
        private HaSocket socket;
        private AudioSource micSource;
        private AudioTrack mic;
        private boolean ended;

        Call(String source, boolean sound, boolean talk) {
            this.source = source;
            this.picture = !sound;
            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.emptyList());
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            peer = factory.createPeerConnection(config, new PeerConnection.Observer() {
                @Override
                public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
                    // The offer goes once every local candidate is in it: on a LAN that takes a moment,
                    // and go2rtc then needs no trickle from us.
                    if (state == PeerConnection.IceGatheringState.COMPLETE) main.post(Call.this::sendOffer);
                }

                @Override
                public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                    if (state == PeerConnection.IceConnectionState.FAILED) main.post(Call.this::failed);
                }

                @Override
                public void onTrack(RtpTransceiver transceiver) {
                    MediaStreamTrack track = transceiver.getReceiver().track();
                    if (track instanceof VideoTrack) ((VideoTrack) track).addSink(view);
                }

                @Override public void onSignalingChange(PeerConnection.SignalingState s) { }
                @Override public void onIceConnectionReceivingChange(boolean b) { }
                @Override public void onIceCandidate(IceCandidate c) { }
                @Override public void onIceCandidatesRemoved(IceCandidate[] c) { }
                @Override public void onAddStream(MediaStream s) { }
                @Override public void onRemoveStream(MediaStream s) { }
                @Override public void onDataChannel(DataChannel d) { }
                @Override public void onRenegotiationNeeded() { }
                @Override public void onAddTrack(RtpReceiver r, MediaStream[] s) { }
            });
            if (peer == null) {
                main.post(this::failed);
                return;
            }
            if (picture) {
                peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                        new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
            } else if (talk) {
                micSource = factory.createAudioSource(new MediaConstraints());
                mic = factory.createAudioTrack("mic", micSource);
                peer.addTransceiver(mic, new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV));
            } else {
                peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                        new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
            }
            peer.createOffer(new Sdp() {
                @Override
                public void onCreateSuccess(SessionDescription offer) {
                    if (!ended) peer.setLocalDescription(new Sdp(), offer);
                }
            }, new MediaConstraints());
        }

        /** The picture's failure is the camera's; the sound's only costs the sound. */
        private void failed() {
            if (ended) return;
            Log.w(TAG, source + (picture ? ": picture" : ": sound") + " call failed");
            if (picture) listener.onFailed();
        }

        private void sendOffer() {
            if (ended || peer.getLocalDescription() == null || socket != null) return;
            String offer = peer.getLocalDescription().description;
            socket = HaSocket.proxied(ha.url(""), "/api/frigate/mse/api/ws?src=" + source, ha.token(), new HaSocket.Listener() {
                private boolean sent;

                @Override
                public void onReady(HaSocket s) {
                    if (sent) return;
                    sent = true;
                    try {
                        s.sendRaw(new JSONObject().put("type", "webrtc/offer").put("value", offer).toString());
                    } catch (Exception e) {
                        Log.w(TAG, "offer: " + e);
                    }
                }

                @Override
                public void onMessage(JSONObject m) {
                    String type = m.optString("type"), value = m.optString("value");
                    main.post(() -> {
                        if (ended) return;
                        switch (type) {
                            case "webrtc/answer":
                                peer.setRemoteDescription(new Sdp(), new SessionDescription(SessionDescription.Type.ANSWER, value));
                                break;
                            case "webrtc/candidate":
                                peer.addIceCandidate(new IceCandidate("0", 0, value));
                                break;
                            case "error":
                                Log.w(TAG, source + ": " + value);
                                failed();
                                break;
                            default:
                        }
                    });
                }
            });
            socket.start();
        }

        void hangUp() {
            ended = true;
            if (socket != null) socket.close();
            if (peer != null) peer.dispose();
            if (mic != null) mic.dispose();
            if (micSource != null) micSource.dispose();
        }
    }

    /** SdpObserver with nothing to do but log failures. */
    private static class Sdp implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription d) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String e) { Log.w(TAG, "sdp: " + e); }
        @Override public void onSetFailure(String e) { Log.w(TAG, "sdp: " + e); }
    }
}
