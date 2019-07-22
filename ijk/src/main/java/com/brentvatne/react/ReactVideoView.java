package com.brentvatne.react;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.media.TimedMetaData;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.webkit.CookieManager;
import android.widget.FrameLayout;

import com.android.vending.expansion.zipfile.APKExpansionSupport;
import com.android.vending.expansion.zipfile.ZipResourceFile;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.misc.ITrackInfo;

//https://github.com/bilibili/ijkplayer/blob/master/android/ijkplayer/ijkplayer-example/src/main/java/tv/danmaku/ijk/media/example/widget/media/IjkVideoView.java
@SuppressLint("ViewConstructor")
public class ReactVideoView extends FrameLayout implements LifecycleEventListener, IMediaPlayer.OnErrorListener, IMediaPlayer.OnPreparedListener, IMediaPlayer.OnBufferingUpdateListener, IMediaPlayer.OnCompletionListener, IMediaPlayer.OnInfoListener, IMediaPlayer.OnSeekCompleteListener {


    @Override
    public boolean onError(IMediaPlayer mp, int what, int extra) {
        WritableMap error = Arguments.createMap();
        error.putInt(EVENT_PROP_WHAT, what);
        error.putInt(EVENT_PROP_EXTRA, extra);
        WritableMap event = Arguments.createMap();
        event.putMap(EVENT_PROP_ERROR, error);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_ERROR.toString(), event);
        return true;
    }

    @Override
    public void onPrepared(IMediaPlayer mp) {
        mMediaPlayerValid = true;
        mVideoDuration = mp.getDuration();

        WritableMap naturalSize = Arguments.createMap();
        naturalSize.putInt(EVENT_PROP_WIDTH, mp.getVideoWidth());
        naturalSize.putInt(EVENT_PROP_HEIGHT, mp.getVideoHeight());
        if (mp.getVideoWidth() > mp.getVideoHeight())
            naturalSize.putString(EVENT_PROP_ORIENTATION, "landscape");
        else
            naturalSize.putString(EVENT_PROP_ORIENTATION, "portrait");

        WritableMap event = Arguments.createMap();
        event.putDouble(EVENT_PROP_DURATION, mVideoDuration / 1000.0);
        event.putDouble(EVENT_PROP_CURRENT_TIME, mp.getCurrentPosition() / 1000.0);
        event.putMap(EVENT_PROP_NATURALSIZE, naturalSize);
        // TODO: Actually check if you can.
        event.putBoolean(EVENT_PROP_FAST_FORWARD, true);
        event.putBoolean(EVENT_PROP_SLOW_FORWARD, true);
        event.putBoolean(EVENT_PROP_SLOW_REVERSE, true);
        event.putBoolean(EVENT_PROP_REVERSE, true);
        event.putBoolean(EVENT_PROP_FAST_FORWARD, true);
        event.putBoolean(EVENT_PROP_STEP_BACKWARD, true);
        event.putBoolean(EVENT_PROP_STEP_FORWARD, true);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_LOAD.toString(), event);

        applyModifiers();

        if (mUseNativeControls) {
            /*initializeMediaControllerIfNeeded();
            mediaController.setMediaPlayer(mMediaPlayer);
            mediaController.setAnchorView(this);

            videoControlHandler.post(new Runnable() {
                @Override
                public void run() {
                    mediaController.setEnabled(true);
                    mediaController.show();
                }
            });*/
        }

        selectTimedMetadataTrack(mp);
    }

    @Override
    public void onBufferingUpdate(IMediaPlayer mp, int percent) {
        selectTimedMetadataTrack(mp);
        mVideoBufferedDuration = (int) Math.round((double) (mVideoDuration * percent) / 100.0);
    }

    @Override
    public void onCompletion(IMediaPlayer mp) {
        isCompleted = true;
        mEventEmitter.receiveEvent(getId(), Events.EVENT_END.toString(), null);
        if (!mRepeat) {
            setKeepScreenOn(false);
        }
    }

    @Override
    public boolean onInfo(IMediaPlayer mp, int what, int extra) {
        switch (what) {
            case MediaPlayer.MEDIA_INFO_BUFFERING_START:
                mEventEmitter.receiveEvent(getId(), Events.EVENT_STALLED.toString(), Arguments.createMap());
                break;
            case MediaPlayer.MEDIA_INFO_BUFFERING_END:
                mEventEmitter.receiveEvent(getId(), Events.EVENT_RESUME.toString(), Arguments.createMap());
                break;
            case MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START:
                mEventEmitter.receiveEvent(getId(), Events.EVENT_READY_FOR_DISPLAY.toString(), Arguments.createMap());
                break;

            default:
        }
        return false;
    }

    @Override
    public void onSeekComplete(IMediaPlayer mp) {
        WritableMap event = Arguments.createMap();
        event.putDouble(EVENT_PROP_CURRENT_TIME, mMediaPlayer.getCurrentPosition() / 1000.0);
        event.putDouble(EVENT_PROP_SEEK_TIME, mSeekTime / 1000.0);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_SEEK.toString(), event);
        mSeekTime = 0;
    }

    public enum Events {
        EVENT_LOAD_START("onVideoLoadStart"),
        EVENT_LOAD("onVideoLoad"),
        EVENT_ERROR("onVideoError"),
        EVENT_PROGRESS("onVideoProgress"),
        EVENT_TIMED_METADATA("onTimedMetadata"),
        EVENT_SEEK("onVideoSeek"),
        EVENT_END("onVideoEnd"),
        EVENT_STALLED("onPlaybackStalled"),
        EVENT_RESUME("onPlaybackResume"),
        EVENT_READY_FOR_DISPLAY("onReadyForDisplay"),
        EVENT_FULLSCREEN_WILL_PRESENT("onVideoFullscreenPlayerWillPresent"),
        EVENT_FULLSCREEN_DID_PRESENT("onVideoFullscreenPlayerDidPresent"),
        EVENT_FULLSCREEN_WILL_DISMISS("onVideoFullscreenPlayerWillDismiss"),
        EVENT_FULLSCREEN_DID_DISMISS("onVideoFullscreenPlayerDidDismiss");

        private final String mName;

        Events(final String name) {
            mName = name;
        }

        @Override
        public String toString() {
            return mName;
        }
    }

    public static final String EVENT_PROP_FAST_FORWARD = "canPlayFastForward";
    public static final String EVENT_PROP_SLOW_FORWARD = "canPlaySlowForward";
    public static final String EVENT_PROP_SLOW_REVERSE = "canPlaySlowReverse";
    public static final String EVENT_PROP_REVERSE = "canPlayReverse";
    public static final String EVENT_PROP_STEP_FORWARD = "canStepForward";
    public static final String EVENT_PROP_STEP_BACKWARD = "canStepBackward";

    public static final String EVENT_PROP_DURATION = "duration";
    public static final String EVENT_PROP_PLAYABLE_DURATION = "playableDuration";
    public static final String EVENT_PROP_SEEKABLE_DURATION = "seekableDuration";
    public static final String EVENT_PROP_CURRENT_TIME = "currentTime";
    public static final String EVENT_PROP_SEEK_TIME = "seekTime";
    public static final String EVENT_PROP_NATURALSIZE = "naturalSize";
    public static final String EVENT_PROP_WIDTH = "width";
    public static final String EVENT_PROP_HEIGHT = "height";
    public static final String EVENT_PROP_ORIENTATION = "orientation";
    public static final String EVENT_PROP_METADATA = "metadata";
    public static final String EVENT_PROP_TARGET = "target";
    public static final String EVENT_PROP_METADATA_IDENTIFIER = "identifier";
    public static final String EVENT_PROP_METADATA_VALUE = "value";

    public static final String EVENT_PROP_ERROR = "error";
    public static final String EVENT_PROP_WHAT = "what";
    public static final String EVENT_PROP_EXTRA = "extra";

    private ThemedReactContext mThemedReactContext;
    private RCTEventEmitter mEventEmitter;
    private IjkMediaPlayer mMediaPlayer;

    private Handler mProgressUpdateHandler = new Handler();
    private Runnable mProgressUpdateRunnable = null;
    private Handler videoControlHandler = new Handler();
    //private MediaController mediaController;

    private String mSrcUriString = null;
    private String mSrcType = "mp4";
    private ReadableMap mRequestHeaders = null;
    private boolean mSrcIsNetwork = false;
    private boolean mSrcIsAsset = false;
    private boolean mRepeat = false;
    private boolean mPaused = false;
    private boolean mMuted = false;
    private float mVolume = 1.0f;
    private float mStereoPan = 0.0f;
    private float mProgressUpdateInterval = 250.0f;
    private float mRate = 1.0f;
    private float mActiveRate = 1.0f;
    private long mSeekTime = 0;
    private boolean mPlayInBackground = false;
    private boolean mBackgroundPaused = false;
    private boolean mIsFullscreen = false;

    private int mMainVer = 0;
    private int mPatchVer = 0;

    private boolean mMediaPlayerValid = false; // True if mMediaPlayer is in prepared, started, paused or completed state.

    private long mVideoDuration = 0;
    private int mVideoBufferedDuration = 0;
    private boolean isCompleted = false;
    private boolean mUseNativeControls = false;

    public ReactVideoView2(ThemedReactContext themedReactContext) {
        super(themedReactContext);

        mThemedReactContext = themedReactContext;
        mEventEmitter = themedReactContext.getJSModule(RCTEventEmitter.class);
        themedReactContext.addLifecycleEventListener(this);

        initializeMediaPlayerIfNeeded();

        mProgressUpdateRunnable = new Runnable() {
            @Override
            public void run() {

                if (mMediaPlayerValid && !isCompleted && !mPaused && !mBackgroundPaused) {
                    WritableMap event = Arguments.createMap();
                    event.putDouble(EVENT_PROP_CURRENT_TIME, mMediaPlayer.getCurrentPosition() / 1000.0);
                    event.putDouble(EVENT_PROP_PLAYABLE_DURATION, mVideoBufferedDuration / 1000.0); //TODO:mBufferUpdateRunnable
                    event.putDouble(EVENT_PROP_SEEKABLE_DURATION, mVideoDuration / 1000.0);
                    mEventEmitter.receiveEvent(getId(), Events.EVENT_PROGRESS.toString(), event);

                    // Check for update after an interval
                    mProgressUpdateHandler.postDelayed(mProgressUpdateRunnable, Math.round(mProgressUpdateInterval));
                }
            }
        };
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mUseNativeControls) {
            initializeMediaControllerIfNeeded();
            //mediaController.show();
        }

        return super.onTouchEvent(event);
    }

   /* @Override
    @SuppressLint("DrawAllocation")
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (!changed || !mMediaPlayerValid) {
            return;
        }

        int videoWidth = getVideoWidth();
        int videoHeight = getVideoHeight();

        if (videoWidth == 0 || videoHeight == 0) {
            return;
        }

        Size viewSize = new Size(getWidth(), getHeight());
        Size videoSize = new Size(videoWidth, videoHeight);
        ScaleManager scaleManager = new ScaleManager(viewSize, videoSize);
        Matrix matrix = scaleManager.getScaleMatrix(mScalableType);
        if (matrix != null) {
            setTransform(matrix);
        }
    }*/

    private void initializeMediaPlayerIfNeeded() {
        if (mMediaPlayer == null) {
            mMediaPlayerValid = false;
            mMediaPlayer = new IjkMediaPlayer();

            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1);
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1);
            mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);

            mMediaPlayer.setScreenOnWhilePlaying(true);
            //mMediaPlayer.setOnVideoSizeChangedListener(this);
            mMediaPlayer.setOnErrorListener(this);
            mMediaPlayer.setOnPreparedListener(this);
            mMediaPlayer.setOnBufferingUpdateListener(this);
            mMediaPlayer.setOnSeekCompleteListener(this);
            mMediaPlayer.setOnCompletionListener(this);
            mMediaPlayer.setOnInfoListener(this);
            if (Build.VERSION.SDK_INT >= 23) {
                //mMediaPlayer.setOnTimedMetaDataAvailableListener(new TimedMetaDataAvailableListener());
            }
        }
    }

    private void initializeMediaControllerIfNeeded() {
        /*if (mediaController == null) {
            mediaController = new MediaController(this.getContext());
        }*/
    }

    public void cleanupMediaPlayerResources() {
        /*if ( mediaController != null ) {
            mediaController.hide();
        }
        if ( mMediaPlayer != null ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                mMediaPlayer.setOnTimedMetaDataAvailableListener(null);
            }
            mMediaPlayerValid = false;
            mMediaPlayer.release();
        }
        if (mIsFullscreen) {
            setFullscreen(false);
        }
        if (mThemedReactContext != null) {
            mThemedReactContext.removeLifecycleEventListener(this);
            mThemedReactContext = null;
        }*/
    }

    public void setSrc(final String uriString, final String type, final boolean isNetwork, final boolean isAsset, final ReadableMap requestHeaders) {
        setSrc(uriString, type, isNetwork, isAsset, requestHeaders, 0, 0);
    }

    public void setSrc(final String uriString, final String type, final boolean isNetwork, final boolean isAsset, final ReadableMap requestHeaders, final int expansionMainVersion, final int expansionPatchVersion) {

        mSrcUriString = uriString;
        mSrcType = type;
        mSrcIsNetwork = isNetwork;
        mSrcIsAsset = isAsset;
        mRequestHeaders = requestHeaders;
        mMainVer = expansionMainVersion;
        mPatchVer = expansionPatchVersion;


        mMediaPlayerValid = false;
        mVideoDuration = 0;
        mVideoBufferedDuration = 0;

        initializeMediaPlayerIfNeeded();
        mMediaPlayer.reset();

        try {
            if (isNetwork) {
                // Use the shared CookieManager to access the cookies
                // set by WebViews inside the same app
                CookieManager cookieManager = CookieManager.getInstance();

                Uri parsedUrl = Uri.parse(uriString);
                Uri.Builder builtUrl = parsedUrl.buildUpon();

                String cookie = cookieManager.getCookie(builtUrl.build().toString());

                Map<String, String> headers = new HashMap<String, String>();

                if (cookie != null) {
                    headers.put("Cookie", cookie);
                }

                if (mRequestHeaders != null) {
                    headers.putAll(toStringMap(mRequestHeaders));
                }

                /* According to https://github.com/react-native-community/react-native-video/pull/537
                 *   there is an issue with this where it can cause a IOException.
                 * TODO: diagnose this exception and fix it
                 */
                mMediaPlayer.setDataSource(mThemedReactContext, parsedUrl, headers);
            } else if (isAsset) {
                if (uriString.startsWith("content://")) {
                    Uri parsedUrl = Uri.parse(uriString);
                    mMediaPlayer.setDataSource(mThemedReactContext, parsedUrl);
                } else {
                    mMediaPlayer.setDataSource(uriString);
                }
            } else {
                ZipResourceFile expansionFile= null;
                AssetFileDescriptor fd= null;
                if(mMainVer>0) {
                    try {
                        expansionFile = APKExpansionSupport.getAPKExpansionZipFile(mThemedReactContext, mMainVer, mPatchVer);
                        fd = expansionFile.getAssetFileDescriptor(uriString.replace(".mp4","") + ".mp4");
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (NullPointerException e) {
                        e.printStackTrace();
                    }
                }
                /*if(fd==null) {
                    int identifier = mThemedReactContext.getResources().getIdentifier(
                            uriString,
                            "drawable",
                            mThemedReactContext.getPackageName()
                    );
                    if (identifier == 0) {
                        identifier = mThemedReactContext.getResources().getIdentifier(
                                uriString,
                                "raw",
                                mThemedReactContext.getPackageName()
                        );
                    }
                    mMediaPlayer.setRawData(identifier);
                }*/
                else {
                    mMediaPlayer.setDataSource(fd.getFileDescriptor());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        WritableMap src = Arguments.createMap();

        WritableMap wRequestHeaders = Arguments.createMap();
        wRequestHeaders.merge(mRequestHeaders);

        src.putString(ReactVideoViewManager.PROP_SRC_URI, uriString);
        src.putString(ReactVideoViewManager.PROP_SRC_TYPE, type);
        src.putMap(ReactVideoViewManager.PROP_SRC_HEADERS, wRequestHeaders);
        src.putBoolean(ReactVideoViewManager.PROP_SRC_IS_NETWORK, isNetwork);
        if(mMainVer>0) {
            src.putInt(ReactVideoViewManager.PROP_SRC_MAINVER, mMainVer);
            if(mPatchVer>0) {
                src.putInt(ReactVideoViewManager.PROP_SRC_PATCHVER, mPatchVer);
            }
        }
        WritableMap event = Arguments.createMap();
        event.putMap(ReactVideoViewManager.PROP_SRC, src);
        mEventEmitter.receiveEvent(getId(), Events.EVENT_LOAD_START.toString(), event);
        isCompleted = false;

        try {
            mMediaPlayer.prepareAsync();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void setRepeatModifier(final boolean repeat) {

        mRepeat = repeat;

        if (mMediaPlayerValid) {
            mMediaPlayer.setLooping(repeat);
        }
    }

    public void setPausedModifier(final boolean paused) {
        mPaused = paused;

        if (!mMediaPlayerValid) {
            return;
        }

        if (mPaused) {
            if (mMediaPlayer.isPlaying()) {
                mMediaPlayer.pause();
            }
        } else {
            if (!mMediaPlayer.isPlaying()) {
                mMediaPlayer.start();
                // Setting the rate unpauses, so we have to wait for an unpause
                if (mRate != mActiveRate) {
                    setRateModifier(mRate);
                }

                // Also Start the Progress Update Handler
                mProgressUpdateHandler.post(mProgressUpdateRunnable);
            }
        }
        setKeepScreenOn(!mPaused);
    }

    // reduces the volume based on stereoPan
    private float calulateRelativeVolume() {
        float relativeVolume = (mVolume * (1 - Math.abs(mStereoPan)));
        // only one decimal allowed
        BigDecimal roundRelativeVolume = new BigDecimal(relativeVolume).setScale(1, BigDecimal.ROUND_HALF_UP);
        return roundRelativeVolume.floatValue();
    }

    public void setMutedModifier(final boolean muted) {
        mMuted = muted;

        if (!mMediaPlayerValid) {
            return;
        }

        if (mMuted) {
            mMediaPlayer.setVolume(0, 0);
        } else if (mStereoPan < 0) {
            // louder on the left channel
            mMediaPlayer.setVolume(mVolume, calulateRelativeVolume());
        } else if (mStereoPan > 0) {
            // louder on the right channel
            mMediaPlayer.setVolume(calulateRelativeVolume(), mVolume);
        } else {
            // same volume on both channels
            mMediaPlayer.setVolume(mVolume, mVolume);
        }
    }

    public void setVolumeModifier(final float volume) {
        mVolume = volume;
        setMutedModifier(mMuted);
    }

    public void setStereoPan(final float stereoPan) {
        mStereoPan = stereoPan;
        setMutedModifier(mMuted);
    }

    public void setProgressUpdateInterval(final float progressUpdateInterval) {
        mProgressUpdateInterval = progressUpdateInterval;
    }

    public void setRateModifier(final float rate) {
        mRate = rate;

        if (mMediaPlayerValid) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!mPaused) { // Applying the rate while paused will cause the video to start
                    /* Per https://stackoverflow.com/questions/39442522/setplaybackparams-causes-illegalstateexception
                     * Some devices throw an IllegalStateException if you set the rate without first calling reset()
                     * TODO: Call reset() then reinitialize the player
                     */
                    try {
                        mMediaPlayer.setSpeed(rate);
                        mActiveRate = rate;
                    } catch (Exception e) {
                        Log.e(ReactVideoViewManager.REACT_CLASS, "Unable to set rate, unsupported on this device");
                    }
                }
            } else {
                Log.e(ReactVideoViewManager.REACT_CLASS, "Setting playback rate is not yet supported on Android versions below 6.0");
            }
        }
    }

    public void setFullscreen(boolean isFullscreen) {
        if (isFullscreen == mIsFullscreen) {
            return; // Avoid generating events when nothing is changing
        }
        mIsFullscreen = isFullscreen;

        Activity activity = mThemedReactContext.getCurrentActivity();
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        View decorView = window.getDecorView();
        int uiOptions;
        if (mIsFullscreen) {
            if (Build.VERSION.SDK_INT >= 19) { // 4.4+
                uiOptions = SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | SYSTEM_UI_FLAG_FULLSCREEN;
            } else {
                uiOptions = SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | SYSTEM_UI_FLAG_FULLSCREEN;
            }
            mEventEmitter.receiveEvent(getId(), Events.EVENT_FULLSCREEN_WILL_PRESENT.toString(), null);
            decorView.setSystemUiVisibility(uiOptions);
            mEventEmitter.receiveEvent(getId(), Events.EVENT_FULLSCREEN_DID_PRESENT.toString(), null);
        } else {
            uiOptions = View.SYSTEM_UI_FLAG_VISIBLE;
            mEventEmitter.receiveEvent(getId(), Events.EVENT_FULLSCREEN_WILL_DISMISS.toString(), null);
            decorView.setSystemUiVisibility(uiOptions);
            mEventEmitter.receiveEvent(getId(), Events.EVENT_FULLSCREEN_DID_DISMISS.toString(), null);
        }
    }

    public void applyModifiers() {
        setRepeatModifier(mRepeat);
        setPausedModifier(mPaused);
        setMutedModifier(mMuted);
        setProgressUpdateInterval(mProgressUpdateInterval);
        setRateModifier(mRate);
    }

    public void setPlayInBackground(final boolean playInBackground) {

        mPlayInBackground = playInBackground;
    }

    public void setControls(boolean controls) {
        this.mUseNativeControls = controls;
    }


    public void seekTo(int msec) {
        if (mMediaPlayerValid) {
            mSeekTime = msec;
            mMediaPlayer.seekTo(msec);
            if (isCompleted && mVideoDuration != 0 && msec < mVideoDuration) {
                isCompleted = false;
            }
        }
    }

    public int getBufferPercentage() {
        return 0;
    }

    public boolean canPause() {
        return true;
    }

    public boolean canSeekBackward() {
        return true;
    }

    public boolean canSeekForward() {
        return true;
    }

    public int getAudioSessionId() {
        return 0;
    }

    // This is not fully tested and does not work for all forms of timed metadata
    @TargetApi(23) // 6.0
    public class TimedMetaDataAvailableListener
            implements MediaPlayer.OnTimedMetaDataAvailableListener
    {
        public void onTimedMetaDataAvailable(MediaPlayer mp, TimedMetaData data) {
            WritableMap event = Arguments.createMap();

            try {
                String rawMeta  = new String(data.getMetaData(), "UTF-8");
                WritableMap id3 = Arguments.createMap();

                id3.putString(EVENT_PROP_METADATA_VALUE, rawMeta.substring(rawMeta.lastIndexOf("\u0003") + 1));
                id3.putString(EVENT_PROP_METADATA_IDENTIFIER, "id3/TDEN");

                WritableArray metadata = new WritableNativeArray();

                metadata.pushMap(id3);

                event.putArray(EVENT_PROP_METADATA, metadata);
                event.putDouble(EVENT_PROP_TARGET, getId());
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }

            mEventEmitter.receiveEvent(getId(), Events.EVENT_TIMED_METADATA.toString(), event);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        mMediaPlayerValid = false;
        super.onDetachedFromWindow();
        setKeepScreenOn(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if(mMainVer>0) {
            setSrc(mSrcUriString, mSrcType, mSrcIsNetwork, mSrcIsAsset, mRequestHeaders, mMainVer, mPatchVer);
        }
        else {
            setSrc(mSrcUriString, mSrcType, mSrcIsNetwork, mSrcIsAsset, mRequestHeaders);
        }
        setKeepScreenOn(true);
    }

    @Override
    public void onHostPause() {
        if (mMediaPlayerValid && !mPaused && !mPlayInBackground) {
            /* Pause the video in background
             * Don't update the paused prop, developers should be able to update it on background
             *  so that when you return to the app the video is paused
             */
            mBackgroundPaused = true;
            mMediaPlayer.pause();
        }
    }

    @Override
    public void onHostResume() {
        mBackgroundPaused = false;
        if (mMediaPlayerValid && !mPlayInBackground && !mPaused) {
            new Handler().post(new Runnable() {
                @Override
                public void run() {
                    // Restore original state
                    setPausedModifier(false);
                }
            });
        }
    }

    @Override
    public void onHostDestroy() {
    }

    /**
     * toStringMap converts a {@link ReadableMap} into a HashMap.
     *
     * @param readableMap The ReadableMap to be conveted.
     * @return A HashMap containing the data that was in the ReadableMap.
     * @see 'Adapted from https://github.com/artemyarulin/react-native-eval/blob/master/android/src/main/java/com/evaluator/react/ConversionUtil.java'
     */
    public static Map<String, String> toStringMap(@Nullable ReadableMap readableMap) {
        Map<String, String> result = new HashMap<>();
        if (readableMap == null)
            return result;

        com.facebook.react.bridge.ReadableMapKeySetIterator iterator = readableMap.keySetIterator();
        while (iterator.hasNextKey()) {
            String key = iterator.nextKey();
            result.put(key, readableMap.getString(key));
        }

        return result;
    }

    // Select track (so we can use it to listen to timed meta data updates)
    private void selectTimedMetadataTrack(IMediaPlayer mp) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try { // It's possible this could throw an exception if the framework doesn't support getting track info
            ITrackInfo[] trackInfo = mp.getTrackInfo();
            for (int i = 0; i < trackInfo.length; ++i) {
                if (trackInfo[i].getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                    mMediaPlayer.selectTrack(i);
                    break;
                }
            }
        } catch (Exception e) {}
    }
}
