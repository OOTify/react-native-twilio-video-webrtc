/**
 * Component to orchestrate the Twilio Video connection and the various video
 * views.
 * <p>
 * Authors:
 * Ralph Pina <ralph.pina@gmail.com>
 * Jonathan Chang <slycoder@gmail.com>
 */
package com.twiliorn.library;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringDef;
import android.util.Log;
import android.view.View;

import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import com.twilio.video.AudioTrack;
import com.twilio.video.AudioTrackStats;
import com.twilio.video.BaseTrackStats;
import com.twilio.video.CameraCapturer;
import com.twilio.video.ConnectOptions;
import com.twilio.video.LocalParticipant;
import com.twilio.video.LocalAudioTrack;
import com.twilio.video.LocalAudioTrackStats;
import com.twilio.video.LocalTrackStats;
import com.twilio.video.LocalVideoTrack;
import com.twilio.video.LocalVideoTrackStats;
import com.twilio.video.Participant;
import com.twilio.video.Room;
import com.twilio.video.RoomState;
import com.twilio.video.StatsListener;
import com.twilio.video.StatsReport;
import com.twilio.video.TrackStats;
import com.twilio.video.TwilioException;
import com.twilio.video.Video;
import com.twilio.video.VideoRenderer;
import com.twilio.video.VideoTrack;
import com.twilio.video.VideoTrackStats;
import com.twilio.video.VideoView;
import com.twilio.video.VideoConstraints;
import com.twilio.video.VideoDimensions;
import org.webrtc.voiceengine.WebRtcAudioManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_AUDIO_CHANGED;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_CAMERA_SWITCHED;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_CONNECTED;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_CONNECT_FAILURE;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_DISCONNECTED;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_PARTICIPANT_CONNECTED;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_PARTICIPANT_DISCONNECTED;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_VIDEO_CHANGED;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_PARTICIPANT_ADDED_VIDEO_TRACK;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_PARTICIPANT_REMOVED_VIDEO_TRACK;
import static com.twiliorn.library.CustomTwilioVideoView.Events.ON_STATS_RECEIVED;

public class CustomTwilioVideoView extends View implements LifecycleEventListener {
    private static final String TAG = "CustomTwilioVideoView";

    @Retention(RetentionPolicy.SOURCE)
    @StringDef({Events.ON_CAMERA_SWITCHED,
            Events.ON_VIDEO_CHANGED,
            Events.ON_AUDIO_CHANGED,
            Events.ON_CONNECTED,
            Events.ON_CONNECT_FAILURE,
            Events.ON_DISCONNECTED,
            Events.ON_PARTICIPANT_CONNECTED,
            Events.ON_PARTICIPANT_DISCONNECTED,
            Events.ON_PARTICIPANT_ADDED_VIDEO_TRACK,
            Events.ON_PARTICIPANT_REMOVED_VIDEO_TRACK,
            Events.ON_STATS_RECEIVED})
    public @interface Events {
        String ON_CAMERA_SWITCHED = "onCameraSwitched";
        String ON_VIDEO_CHANGED = "onVideoChanged";
        String ON_AUDIO_CHANGED = "onAudioChanged";
        String ON_CONNECTED = "onRoomDidConnect";
        String ON_CONNECT_FAILURE = "onRoomDidFailToConnect";
        String ON_DISCONNECTED = "onRoomDidDisconnect";
        String ON_PARTICIPANT_CONNECTED = "onRoomParticipantDidConnect";
        String ON_PARTICIPANT_DISCONNECTED = "onRoomParticipantDidDisconnect";
        String ON_PARTICIPANT_ADDED_VIDEO_TRACK = "onParticipantAddedVideoTrack";
        String ON_PARTICIPANT_REMOVED_VIDEO_TRACK = "onParticipantRemovedVideoTrack";
        String ON_STATS_RECEIVED = "onStatsReceived";
    }

    private final ThemedReactContext themedReactContext;
    private final RCTEventEmitter eventEmitter;

    /*
     * A Room represents communication between the client and one or more participants.
     */
    private Room room;
    private String roomName = null;
    private String accessToken = null;
    private LocalParticipant localParticipant;

    /*
     * A VideoView receives frames from a local or remote video track and renders them
     * to an associated view.
     */
    private static VideoView primaryVideoView;
    private static VideoView thumbnailVideoView;
    private static List<VideoTrack> participantVideoTracks = new ArrayList<VideoTrack>();
    private static LocalVideoTrack localVideoTrack;

    private static CameraCapturer cameraCapturer;
    private LocalAudioTrack localAudioTrack;
    private AudioManager audioManager;
    private int previousAudioMode;
    private boolean disconnectedFromOnDestroy;
    private IntentFilter intentFilter;
    private BecomingNoisyReceiver myNoisyAudioStreamReceiver;

    public CustomTwilioVideoView(ThemedReactContext context) {
        super(context);
        this.themedReactContext = context;
        this.eventEmitter = themedReactContext.getJSModule(RCTEventEmitter.class);

        // add lifecycle for onResume and on onPause
        themedReactContext.addLifecycleEventListener(this);

        /*
         * Enable changing the volume using the up/down keys during a conversation
         */
        if (themedReactContext.getCurrentActivity() != null) {
            themedReactContext.getCurrentActivity().setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        }
        /*
         * Needed for setting/abandoning audio focus during call
         */
        audioManager = (AudioManager) themedReactContext.getSystemService(Context.AUDIO_SERVICE);
        myNoisyAudioStreamReceiver = new BecomingNoisyReceiver();
        intentFilter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
    }

    // ===== SETUP =================================================================================

    private VideoConstraints buildVideoConstraints() {
        return new VideoConstraints.Builder()
                .minVideoDimensions(VideoDimensions.CIF_VIDEO_DIMENSIONS)
                .maxVideoDimensions(VideoDimensions.CIF_VIDEO_DIMENSIONS)
                .minFps(5)
                .maxFps(15)
                .build();
    }

    private void createLocalMedia() {
        // Share your microphone
        localAudioTrack = LocalAudioTrack.create(getContext(), true);
        Log.i("CustomTwilioVideoView", "Create local media");

        // Share your camera
        cameraCapturer = new CameraCapturer(
                getContext(),
                CameraCapturer.CameraSource.FRONT_CAMERA,
                new CameraCapturer.Listener() {
                    @Override
                    public void onFirstFrameAvailable() {
                        Log.i("CustomTwilioVideoView", "Got a local camera track");
                    }

                    @Override
                    public void onCameraSwitched() {

                    }

                    @Override
                    public void onError(int i) {
                        Log.i("CustomTwilioVideoView", "Error getting camera");
                    }
                }
        );

        if (cameraCapturer.getSupportedFormats().size() > 0) {
            localVideoTrack = LocalVideoTrack.create(getContext(), true, cameraCapturer, buildVideoConstraints());
            if (thumbnailVideoView != null && localVideoTrack != null) {
                localVideoTrack.addRenderer(thumbnailVideoView);
            }
            setThumbnailMirror();
        }
        connectToRoom();
    }

    // ===== LIFECYCLE EVENTS ======================================================================

    @Override
    public void onHostResume() {
        /*
         * In case it wasn't set.
         */
        if (themedReactContext.getCurrentActivity() != null) {
            /*
            * If the local video track was released when the app was put in the background, recreate.
            */
            if (cameraCapturer != null && localVideoTrack == null) {
                localVideoTrack = LocalVideoTrack.create(getContext(), true, cameraCapturer, buildVideoConstraints());
            }

            if (localVideoTrack != null) {
                if (thumbnailVideoView != null) {
                    localVideoTrack.addRenderer(thumbnailVideoView);
                }

                /*
                * If connected to a Room then share the local video track.
                */
                if (localParticipant != null) {
                    localParticipant.addVideoTrack(localVideoTrack);
                }
            }

            themedReactContext.getCurrentActivity().setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);

        }
    }

    @Override
    public void onHostPause() {
        Log.i("CustomTwilioVideoView", "Host pause");
        /*
         * Release the local video track before going in the background. This ensures that the
         * camera can be used by other applications while this app is in the background.
         */
//        if (localVideoTrack != null) {
////            /*
////             * If this local video track is being shared in a Room, remove from local
////             * participant before releasing the video track. Participants will be notified that
////             * the track has been removed.
////             */
////            if (localParticipant != null) {
////                localParticipant.removeVideoTrack(localVideoTrack);
////            }
////
////            localVideoTrack.release();
////            localVideoTrack = null;
////        }
    }

    @Override
    public void onHostDestroy() {
        /*
         * Always disconnect from the room before leaving the Activity to
         * ensure any memory allocated to the Room resource is freed.
         */
        if (room != null && room.getState() != RoomState.DISCONNECTED) {
            room.disconnect();
            disconnectedFromOnDestroy = true;
        }

        /*
         * Release the local media ensuring any memory allocated to audio or video is freed.
         */
        if (localVideoTrack != null) {
            localVideoTrack.release();
            localVideoTrack = null;
        }

        if (localAudioTrack != null) {
            localAudioTrack.release();
            localAudioTrack = null;
        }
    }

    // ====== CONNECTING ===========================================================================

    public void connectToRoomWrapper(String roomName, String accessToken) {
        this.roomName = roomName;
        this.accessToken = accessToken;

        Log.i("CustomTwilioVideoView", "Starting connect flow");

        if (cameraCapturer == null) {
            createLocalMedia();
        } else {
            localAudioTrack = LocalAudioTrack.create(getContext(), true);
            connectToRoom();
        }
    }

    public void connectToRoom() {
        /*
         * Create a VideoClient allowing you to connect to a Room
         */
        setAudioFocus(true);
        ConnectOptions.Builder connectOptionsBuilder = new ConnectOptions.Builder(this.accessToken);

        if (this.roomName != null) {
            connectOptionsBuilder.roomName(this.roomName);
        }

        if (localAudioTrack != null) {
            connectOptionsBuilder.audioTracks(Collections.singletonList(localAudioTrack));
        }

        if (localVideoTrack != null) {
            connectOptionsBuilder.videoTracks(Collections.singletonList(localVideoTrack));
        }

        room = Video.connect(getContext(), connectOptionsBuilder.build(), roomListener());
    }

    private void setAudioFocus(boolean focus) {
        if (focus) {
            previousAudioMode = audioManager.getMode();
            // Request audio focus before making any device switch.
            audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            /*
             * Use MODE_IN_COMMUNICATION as the default audio mode. It is required
             * to be in this mode when playout and/or recording starts for the best
             * possible VoIP performance. Some devices have difficulties with
             * speaker mode if this is not set.
             */
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(!audioManager.isWiredHeadsetOn());
            getContext().registerReceiver(myNoisyAudioStreamReceiver, intentFilter);
        } else {
            audioManager.setMode(previousAudioMode);
            audioManager.abandonAudioFocus(null);
            audioManager.setSpeakerphoneOn(false);
            getContext().unregisterReceiver(myNoisyAudioStreamReceiver);
        }
    }

    private class BecomingNoisyReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_HEADSET_PLUG.equals(intent.getAction())) {
                audioManager.setSpeakerphoneOn(!audioManager.isWiredHeadsetOn());
            }
        }
    }

    // ====== DISCONNECTING ========================================================================

    public void disconnect() {
        if (room != null) {
            room.disconnect();
        }
        if (localAudioTrack != null) {
            localAudioTrack.release();
            localAudioTrack = null;
        }
        if (localVideoTrack != null) {
            localVideoTrack.release();
            localVideoTrack = null;
        }
    }

    // ===== BUTTON LISTENERS ======================================================================
    private static void setThumbnailMirror() {
        if (cameraCapturer != null) {
            CameraCapturer.CameraSource cameraSource = cameraCapturer.getCameraSource();
            final boolean isBackCamera = (cameraSource == CameraCapturer.CameraSource.BACK_CAMERA);
            if (thumbnailVideoView != null && thumbnailVideoView.getVisibility() == View.VISIBLE) {
                thumbnailVideoView.setMirror(isBackCamera);
            }
        }
    }

    public void switchCamera() {
        if (cameraCapturer != null) {
            cameraCapturer.switchCamera();
            setThumbnailMirror();
            CameraCapturer.CameraSource cameraSource = cameraCapturer.getCameraSource();
            final boolean isBackCamera = cameraSource == CameraCapturer.CameraSource.BACK_CAMERA;
            WritableMap event = new WritableNativeMap();
            event.putBoolean("isBackCamera", isBackCamera);
            pushEvent(CustomTwilioVideoView.this, ON_CAMERA_SWITCHED, event);
        }
    }

    public void toggleVideo(boolean enabled) {
        if (localVideoTrack != null) {
            localVideoTrack.enable(enabled);

            WritableMap event = new WritableNativeMap();
            event.putBoolean("videoEnabled", enabled);
            pushEvent(CustomTwilioVideoView.this, ON_VIDEO_CHANGED, event);
        }
    }

    public void toggleAudio(boolean enabled) {
        if (localAudioTrack != null) {
            localAudioTrack.enable(enabled);

            WritableMap event = new WritableNativeMap();
            event.putBoolean("audioEnabled", enabled);
            pushEvent(CustomTwilioVideoView.this, ON_AUDIO_CHANGED, event);
        }
    }

    public void toggleSpeaker(boolean enabled) {
        audioManager = (AudioManager) themedReactContext.getSystemService(Context.AUDIO_SERVICE);
        audioManager.setSpeakerphoneOn(!enabled);
    }

    private void convertBaseTrackStats(BaseTrackStats bs, WritableMap result) {
        result.putString("codec", bs.codec);
        result.putInt("packetsLost", bs.packetsLost);
        result.putString("ssrc", bs.ssrc);
        result.putDouble("timestamp", bs.timestamp);
        result.putString("trackId", bs.trackId);
    }

    private void convertLocalTrackStats(LocalTrackStats ts, WritableMap result) {
        result.putDouble("bytesSent", ts.bytesSent);
        result.putInt("packetsSent", ts.packetsSent);
        result.putDouble("roundTripTime", ts.roundTripTime);
    }

    private void convertTrackStats(TrackStats ts, WritableMap result) {
        result.putDouble("bytesReceived", ts.bytesReceived);
        result.putInt("packetsReceived", ts.packetsReceived);
    }

    private WritableMap convertAudioTrackStats(AudioTrackStats as) {
        WritableMap result = new WritableNativeMap();
        result.putInt("audioLevel", as.audioLevel);
        result.putInt("jitter", as.jitter);
        convertBaseTrackStats(as, result);
        convertTrackStats(as, result);
        return result;
    }

    private WritableMap convertLocalAudioTrackStats(LocalAudioTrackStats as) {
        WritableMap result = new WritableNativeMap();
        result.putInt("audioLevel", as.audioLevel);
        result.putInt("jitter", as.jitter);
        convertBaseTrackStats(as, result);
        convertLocalTrackStats(as, result);
        return result;
    }

    private WritableMap convertVideoTrackStats(VideoTrackStats vs) {
        WritableMap result = new WritableNativeMap();
        WritableMap dimensions = new WritableNativeMap();
        dimensions.putInt("height", vs.dimensions.height);
        dimensions.putInt("width", vs.dimensions.width);
        result.putMap("dimensions", dimensions);
        result.putInt("frameRate", vs.frameRate);
        convertBaseTrackStats(vs, result);
        convertTrackStats(vs, result);
        return result;
    }

    private WritableMap convertLocalVideoTrackStats(LocalVideoTrackStats vs) {
        WritableMap result = new WritableNativeMap();
        WritableMap dimensions = new WritableNativeMap();
        dimensions.putInt("height", vs.dimensions.height);
        dimensions.putInt("width", vs.dimensions.width);
        result.putMap("dimensions", dimensions);
        result.putInt("frameRate", vs.frameRate);
        convertBaseTrackStats(vs, result);
        convertLocalTrackStats(vs, result);
        return result;
    }

    public void getStats() {
        if (room != null) {
            room.getStats(new StatsListener() {
                @Override
                public void onStats(List<StatsReport> statsReports) {
                    WritableMap event = new WritableNativeMap();
                    for (StatsReport sr : statsReports) {
                        WritableMap connectionStats = new WritableNativeMap();
                        WritableArray as = new WritableNativeArray();
                        for (AudioTrackStats s : sr.getAudioTrackStats()) {
                            as.pushMap(convertAudioTrackStats(s));
                        }
                        connectionStats.putArray("audioTrackStats", as);

                        WritableArray vs = new WritableNativeArray();
                        for (VideoTrackStats s : sr.getVideoTrackStats()) {
                            vs.pushMap(convertVideoTrackStats(s));
                        }
                        connectionStats.putArray("videoTrackStats", vs);

                        WritableArray las = new WritableNativeArray();
                        for (LocalAudioTrackStats s : sr.getLocalAudioTrackStats()) {
                            las.pushMap(convertLocalAudioTrackStats(s));
                        }
                        connectionStats.putArray("localAudioTrackStats", las);

                        WritableArray lvs = new WritableNativeArray();
                        for (LocalVideoTrackStats s : sr.getLocalVideoTrackStats()) {
                            lvs.pushMap(convertLocalVideoTrackStats(s));
                        }
                        connectionStats.putArray("localVideoTrackStats", lvs);
                        event.putMap(sr.getPeerConnectionId(), connectionStats);
                    }
                    pushEvent(CustomTwilioVideoView.this, ON_STATS_RECEIVED, event);
                }
            });
        }
    }

    public void disableOpenSLES() {
      WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true);
    }

    // ====== ROOM LISTENER ========================================================================

    /*
     * Room events listener
     */
    private Room.Listener roomListener() {
        return new Room.Listener() {
            @Override
            public void onConnected(Room room) {
                localParticipant = room.getLocalParticipant();
                WritableMap event = new WritableNativeMap();
                event.putString("room", room.getName());
                List<Participant> participants = room.getParticipants();

                WritableArray participantsNames = new WritableNativeArray();
                for (Participant participant : participants) {
                    participantsNames.pushString(participant.getIdentity());
                }
                event.putArray("participantsNames", participantsNames);

                pushEvent(CustomTwilioVideoView.this, ON_CONNECTED, event);

                //noinspection LoopStatementThatDoesntLoop
                for (Participant participant : participants) {
                    addParticipant(participant);
                    break;
                }
            }

            @Override
            public void onConnectFailure(Room room, TwilioException e) {
                WritableMap event = new WritableNativeMap();
                event.putString("reason", e.getExplanation());
                pushEvent(CustomTwilioVideoView.this, ON_CONNECT_FAILURE, event);
            }

            @Override
            public void onDisconnected(Room room, TwilioException e) {
                WritableMap event = new WritableNativeMap();
                if (localParticipant != null) {
                    event.putString("participant", localParticipant.getIdentity());
                    pushEvent(CustomTwilioVideoView.this, ON_DISCONNECTED, event);
                }

                localParticipant = null;
                roomName = null;
                accessToken = null;


                CustomTwilioVideoView.this.room = null;
                // Only reinitialize the UI if disconnect was not called from onDestroy()
                if (!disconnectedFromOnDestroy) {
                    setAudioFocus(false);
                }
            }

            @Override
            public void onParticipantConnected(Room room, Participant participant) {
                addParticipant(participant);
            }

            @Override
            public void onParticipantDisconnected(Room room, Participant participant) {
                removeParticipant(participant);
            }

            @Override
            public void onRecordingStarted(Room room) {
            }

            @Override
            public void onRecordingStopped(Room room) {
            }
        };
    }

    /*
     * Called when participant joins the room
     */
    private void addParticipant(Participant participant) {
        Log.i("CustomTwilioVideoView", "ADD PARTICIPANT ");

        WritableMap event = new WritableNativeMap();
        event.putString("participant", participant.getIdentity());

        pushEvent(this, ON_PARTICIPANT_CONNECTED, event);
        /*
         * Add participant renderer
         */
        if (participant.getVideoTracks().size() > 0) {
            Log.i("CustomTwilioVideoView", "Participant DOES HAVE VIDEO TRACKS");


            addParticipantVideo(
                    participant,
                    participant.getVideoTracks().get(0)
            );
        } else {
            Log.i("CustomTwilioVideoView", "Participant DOES NOT HAVE VIDEO TRACKS");

        }

        /*
         * Start listening for participant media events
         */
        participant.setListener(mediaListener());
    }

    /*
     * Called when participant leaves the room
     */
    private void removeParticipant(Participant participant) {
        WritableMap event = new WritableNativeMap();
        event.putString("participant", participant.getIdentity());
        pushEvent(this, ON_PARTICIPANT_DISCONNECTED, event);

        /*
         * Remove participant renderer
         */
        if (participant.getVideoTracks().size() > 0) {
            removeParticipantVideo(
                    participant,
                    participant.getVideoTracks().get(0)
            );
        }
        //something about this breaking.
        //participant.setListener(null);
    }


    // ====== MEDIA LISTENER =======================================================================

    private Participant.Listener mediaListener() {
        return new Participant.Listener() {
            @Override
            public void onAudioTrackAdded(Participant participant, AudioTrack audioTrack) {

            }

            @Override
            public void onAudioTrackRemoved(Participant participant, AudioTrack audioTrack) {

            }

            @Override
            public void onVideoTrackAdded(Participant participant, VideoTrack videoTrack) {
                Log.i("CustomTwilioVideoView", "Participant ADDED TRACK");

                addParticipantVideo(participant, videoTrack);
            }

            @Override
            public void onVideoTrackRemoved(Participant participant, VideoTrack videoTrack) {
                Log.i("CustomTwilioVideoView", "Participant REMOVED TRACK");
                removeParticipantVideo(participant, videoTrack);
            }

            @Override
            public void onAudioTrackEnabled(Participant participant, AudioTrack audioTrack) {

            }

            @Override
            public void onAudioTrackDisabled(Participant participant, AudioTrack audioTrack) {

            }

            @Override
            public void onVideoTrackEnabled(Participant participant, VideoTrack videoTrack) {

            }

            @Override
            public void onVideoTrackDisabled(Participant participant, VideoTrack videoTrack) {

            }
        };
    }

    private WritableMap buildParticipantVideoEvent(Participant participant, VideoTrack videoTrack) {
        WritableMap participantMap = new WritableNativeMap();
        participantMap.putString("identity", participant.getIdentity());

        WritableMap trackMap = new WritableNativeMap();
        trackMap.putString("trackId", videoTrack.getTrackId());

        WritableMap event = new WritableNativeMap();
        event.putMap("participant", participantMap);
        event.putMap("track", trackMap);
        return event;
    }

    private void addParticipantVideo(Participant participant, VideoTrack videoTrack) {
        Log.i("CustomTwilioVideoView", "add Participant Video");

        participantVideoTracks.add(participantVideoTracks.size(), videoTrack);

        WritableMap event = this.buildParticipantVideoEvent(participant, videoTrack);
        pushEvent(CustomTwilioVideoView.this, ON_PARTICIPANT_ADDED_VIDEO_TRACK, event);
    }

    private void removeParticipantVideo(Participant participant, VideoTrack deleteVideoTrack) {
        Log.i("CustomTwilioVideoView", "Remove participant");
        List<VideoTrack> newParticipantTracks = new ArrayList<VideoTrack>();
        for (VideoTrack videoTrack : participantVideoTracks) {
            Log.i("CustomTwilioVideoView", videoTrack.getTrackId());

            if (videoTrack.getTrackId().equals(deleteVideoTrack.getTrackId())) {
                Log.i("CustomTwilioVideoView", "FOUND THE MATCHING TRACK");

            } else {
                newParticipantTracks.add(newParticipantTracks.size(), videoTrack);
            }
        }

        participantVideoTracks = newParticipantTracks;
        WritableMap event = this.buildParticipantVideoEvent(participant, deleteVideoTrack);
        pushEvent(CustomTwilioVideoView.this, ON_PARTICIPANT_REMOVED_VIDEO_TRACK, event);
    }
    // ===== EVENTS TO RN ==========================================================================

    void pushEvent(View view, String name, WritableMap data) {
        eventEmitter.receiveEvent(view.getId(), name, data);
    }

    public static void registerPrimaryVideoView(VideoView v, String trackId) {
        String sizeString = Integer.toString(participantVideoTracks.size());

        Log.i("CustomTwilioVideoView", "register Primary Video");
        Log.i("CustomTwilioVideoView", trackId);
        primaryVideoView = v;

        if (participantVideoTracks != null) {

            Log.i("CustomTwilioVideoView", "Found Partiipant tracks");

            for (VideoTrack videoTrack : participantVideoTracks) {
                Log.i("CustomTwilioVideoView", videoTrack.getTrackId());

                List<VideoRenderer> renders = videoTrack.getRenderers();
                if (videoTrack.getTrackId().equals(trackId)) {
                    Log.i("CustomTwilioVideoView", "FOUND THE MATCHING TRACK");
                    videoTrack.addRenderer(v);
                } else {
                    videoTrack.removeRenderer(v);

                }
            }
        }
    }

    public static void registerThumbnailVideoView(VideoView v) {
        thumbnailVideoView = v;
        if (localVideoTrack != null) {
            localVideoTrack.addRenderer(v);
        }
        setThumbnailMirror();
    }
}
