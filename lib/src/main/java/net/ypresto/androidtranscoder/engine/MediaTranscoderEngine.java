/*
 * Copyright (C) 2014 Yuya Tanaka
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ypresto.androidtranscoder.engine;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.util.Log;

import net.ypresto.androidtranscoder.format.MediaFormatStrategy;
import net.ypresto.androidtranscoder.utils.MediaExtractorUtils;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.IllegalFormatException;

/**
 * Internal engine, do not use this directly.
 */
// TODO: treat encrypted data
public class MediaTranscoderEngine {
    private static final String TAG = "MediaTranscoderEngine";
    private static final double PROGRESS_UNKNOWN = -1.0;
    private static final long SLEEP_TO_WAIT_TRACK_TRANSCODERS = 10;
    private static final long PROGRESS_INTERVAL_STEPS = 10;
    // check average framerate for the first second
    private static final long FRAMERATE_CHECK_AT_PRESENTATION_TIME_US = 1000000;
    private FileDescriptor mInputFileDescriptor;
    private TrackTranscoder mVideoTrackTranscoder;
    private TrackTranscoder mAudioTrackTranscoder;
    private MediaExtractor mExtractor;
    private MediaMuxer mMuxer;
    private volatile double mProgress;
    private ProgressCallback mProgressCallback;
    private long mDurationUs;
    private long mMaximumDurationUs;
    private int mMaximumFrameRate;
    private boolean mAvcOutputBaselineValidationEnabled = true;

    /**
     * Do not use this constructor unless you know what you are doing.
     */
    public MediaTranscoderEngine() {
    }

    public void setDataSource(FileDescriptor fileDescriptor) {
        mInputFileDescriptor = fileDescriptor;
    }

    public ProgressCallback getProgressCallback() {
        return mProgressCallback;
    }

    public void setProgressCallback(ProgressCallback progressCallback) {
        mProgressCallback = progressCallback;
    }

    public void setMaximumDurationUs(long maximumDurationUs) {
        mMaximumDurationUs = maximumDurationUs;
    }

    public void setAvcOutputBaselineProfileValidationEnabled(boolean enabled) {
        mAvcOutputBaselineValidationEnabled = enabled;
    }

    public void setMaximumFrameRate(int maximumFrameRate) {
        mMaximumFrameRate = maximumFrameRate;
    }

    /**
     * NOTE: This method is thread safe.
     */
    public double getProgress() {
        return mProgress;
    }

    /**
     * Run video transcoding. Blocks current thread.
     * Audio data will not be transcoded; original stream will be wrote to output file.
     *
     * @param outputPath     File path to output transcoded video file.
     * @param formatStrategy Output format strategy.
     * @throws IOException                  when input or output file could not be opened.
     * @throws InvalidOutputFormatException when output format is not supported.
     * @throws InterruptedException         when cancel to transcode.
     */
    public void transcodeVideo(String outputPath, MediaFormatStrategy formatStrategy) throws IOException, InterruptedException {
        if (outputPath == null) {
            throw new NullPointerException("Output path cannot be null.");
        }
        if (mInputFileDescriptor == null) {
            throw new IllegalStateException("Data source is not set.");
        }
        try {
            // NOTE: use single extractor to keep from running out audio track fast.
            mExtractor = new MediaExtractor();
            mExtractor.setDataSource(mInputFileDescriptor);
            mMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            setupMetadata();
            setupTrackTranscoders(formatStrategy);
            runPipelines();
            mMuxer.stop();
        } finally {
            try {
                if (mVideoTrackTranscoder != null) {
                    mVideoTrackTranscoder.release();
                    mVideoTrackTranscoder = null;
                }
                if (mAudioTrackTranscoder != null) {
                    mAudioTrackTranscoder.release();
                    mAudioTrackTranscoder = null;
                }
                if (mExtractor != null) {
                    mExtractor.release();
                    mExtractor = null;
                }
            } catch (RuntimeException e) {
                // Too fatal to make alive the app, because it may leak native resources.
                //noinspection ThrowFromFinallyBlock
                throw new Error("Could not shutdown extractor, codecs and muxer pipeline.", e);
            }
            try {
                if (mMuxer != null) {
                    mMuxer.release();
                    mMuxer = null;
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to release muxer.", e);
            }
        }
    }

    private void setupMetadata() throws IOException {
        MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
        mediaMetadataRetriever.setDataSource(mInputFileDescriptor);

        String rotationString = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
        try {
            mMuxer.setOrientationHint(Integer.parseInt(rotationString));
        } catch (NumberFormatException e) {
            // skip
        }

        // TODO: parse ISO 6709
        // String locationString = mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
        // mMuxer.setLocation(Integer.getInteger(rotationString, 0));

        try {
            mDurationUs = Long.parseLong(mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000;
        } catch (NumberFormatException e) {
            mDurationUs = -1;
        }
        Log.d(TAG, "Duration (us): " + mDurationUs);
    }

    private void setupTrackTranscoders(MediaFormatStrategy formatStrategy) {
        MediaExtractorUtils.TrackResult trackResult = MediaExtractorUtils.getFirstVideoAndAudioTrack(mExtractor);
        MediaFormat videoOutputFormat = formatStrategy.createVideoOutputFormat(trackResult.mVideoTrackFormat);
        MediaFormat audioOutputFormat = formatStrategy.createAudioOutputFormat(trackResult.mAudioTrackFormat);
        if (videoOutputFormat == null && audioOutputFormat == null) {
            throw new InvalidOutputFormatException("MediaFormatStrategy returned pass-through for both video and audio. No transcoding is necessary.");
        }
        QueuedMuxer queuedMuxer = new QueuedMuxer(mMuxer, new QueuedMuxer.Listener() {
            @Override
            public void onDetermineOutputFormat() {
                MediaFormatValidator.validateVideoOutputFormat(mVideoTrackTranscoder.getDeterminedFormat(), mAvcOutputBaselineValidationEnabled);
                MediaFormatValidator.validateAudioOutputFormat(mAudioTrackTranscoder.getDeterminedFormat());
            }
        });

        if (videoOutputFormat == null) {
            mVideoTrackTranscoder = new PassThroughTrackTranscoder(mExtractor, trackResult.mVideoTrackIndex, queuedMuxer, QueuedMuxer.SampleType.VIDEO);
        } else {
            mVideoTrackTranscoder = new VideoTrackTranscoder(mExtractor, trackResult.mVideoTrackIndex, videoOutputFormat, queuedMuxer);
        }
        mVideoTrackTranscoder.setup();
        if (audioOutputFormat == null) {
            mAudioTrackTranscoder = new PassThroughTrackTranscoder(mExtractor, trackResult.mAudioTrackIndex, queuedMuxer, QueuedMuxer.SampleType.AUDIO);
        } else {
            throw new UnsupportedOperationException("Transcoding audio tracks currently not supported.");
        }
        mAudioTrackTranscoder.setup();
        mExtractor.selectTrack(trackResult.mVideoTrackIndex);
        mExtractor.selectTrack(trackResult.mAudioTrackIndex);
    }

    private void runPipelines() {
        long firstPresentationTimestamp = -1;
        boolean framerateCheckDone = false;
        long loopCount = 0;
        if (mDurationUs <= 0) {
            double progress = PROGRESS_UNKNOWN;
            mProgress = progress;
            if (mProgressCallback != null) mProgressCallback.onProgress(progress); // unknown
        } else {
            if (mMaximumDurationUs > 0 && mDurationUs > mMaximumDurationUs) {
                Log.d(TAG, "Limiting duration to " + mMaximumDurationUs + " us (was " + mDurationUs + " us)");
                mDurationUs = mMaximumDurationUs;
            }
        }
        while (!(mVideoTrackTranscoder.isFinished() && mAudioTrackTranscoder.isFinished())) {
            boolean stepped = mVideoTrackTranscoder.stepPipeline()
                    || mAudioTrackTranscoder.stepPipeline();
            loopCount++;

            long videoTimeUs = mVideoTrackTranscoder.getWrittenPresentationTimeUs();
            long audioTimeUs = mAudioTrackTranscoder.getWrittenPresentationTimeUs();
            if (firstPresentationTimestamp == -1) {
                firstPresentationTimestamp = videoTimeUs;
            } else if (!framerateCheckDone &&
                    mMaximumFrameRate > 0 &&
                    videoTimeUs > firstPresentationTimestamp + FRAMERATE_CHECK_AT_PRESENTATION_TIME_US) {
                Log.d(TAG, "Estimating frame rate");
                framerateCheckDone = true;
                int frameCount = mVideoTrackTranscoder.getSampleCount();
                long timedelta = videoTimeUs - firstPresentationTimestamp;
                Log.d(TAG, "frameCount=" + frameCount + " timedelta=" + timedelta);
                if (timedelta > 0) {
                    float framerate = frameCount * 1000000.0f / timedelta;
                    if (framerate > mMaximumFrameRate) {
                        throw new IllegalArgumentException(
                                String.format("Estimated frame rate %.1f exceeds the set limit of %d fps", framerate, mMaximumFrameRate));
                    }
                }
            }

            if (mMaximumDurationUs > 0 && (videoTimeUs > mMaximumDurationUs || audioTimeUs > mMaximumDurationUs)) {
                // Reached limit
                break;
            }

            if (mDurationUs > 0 && loopCount % PROGRESS_INTERVAL_STEPS == 0) {
                double videoProgress = mVideoTrackTranscoder.isFinished() ? 1.0 : Math.min(1.0, (double) videoTimeUs / mDurationUs);
                double audioProgress = mAudioTrackTranscoder.isFinished() ? 1.0 : Math.min(1.0, (double) audioTimeUs / mDurationUs);
                double progress = (videoProgress + audioProgress) / 2.0;
                mProgress = progress;
                if (mProgressCallback != null) mProgressCallback.onProgress(progress);
            }
            if (!stepped) {
                try {
                    Thread.sleep(SLEEP_TO_WAIT_TRACK_TRANSCODERS);
                } catch (InterruptedException e) {
                    // nothing to do
                }
            }
        }
    }

    public interface ProgressCallback {
        /**
         * Called to notify progress. Same thread which initiated transcode is used.
         *
         * @param progress Progress in [0.0, 1.0] range, or negative value if progress is unknown.
         */
        void onProgress(double progress);
    }
}
