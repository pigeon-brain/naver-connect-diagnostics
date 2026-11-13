package local.son.connectdiagnostics;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.SystemClock;

final class AudioProbe {
    interface Listener {
        void onStatus(String status);
        void onLevel(int percent);
    }

    private static final int OUTPUT_RATE = 48000;
    private static final int INPUT_RATE = 16000;
    private final Activity activity;
    private final Listener listener;
    private final Object audioLock = new Object();
    private volatile Thread worker;

    AudioProbe(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    void tone(int channel) {
        start(() -> playTone(channel));
    }

    void listen() {
        start(this::readMicrophone);
    }

    void stop() {
        Thread previous = worker;
        worker = null;
        if (previous != null) previous.interrupt();
    }

    private void start(Runnable probe) {
        stop();
        worker = new Thread(() -> {
            synchronized (audioLock) {
                if (Thread.currentThread() == worker) probe.run();
            }
        }, "audio-probe");
        worker.start();
    }

    private void deliver(Runnable update) {
        Thread source = Thread.currentThread();
        activity.runOnUiThread(() -> {
            if (worker == source) update.run();
        });
    }

    private void status(String value) {
        deliver(() -> listener.onStatus(value));
    }

    private void playTone(int channel) {
        AudioTrack track = null;
        try {
            short[] samples = toneSamples(channel);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(OUTPUT_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.length * 2).build();
            if (track.write(samples, 0, samples.length) != samples.length) {
                status("시험음 준비 실패");
                return;
            }
            if (Thread.currentThread() != worker) return;
            track.play();
            status("시험음 재생 중");
            long deadline = SystemClock.elapsedRealtime() + 1800;
            AudioDeviceInfo routed = null;
            while (track.getPlaybackHeadPosition() < OUTPUT_RATE
                    && SystemClock.elapsedRealtime() < deadline) {
                AudioDeviceInfo current = track.getRoutedDevice();
                if (current != null) routed = current;
                Thread.sleep(30);
            }
            String output = routed == null ? "출력 장치 조회 불가" : "출력 장치 " + routed.getProductName();
            if (track.getPlaybackHeadPosition() < OUTPUT_RATE) {
                status("시험음 재생 완료 확인 실패 · " + output);
            } else {
                status("시험음 재생 완료 · " + output + " · 들렸는지 확인하세요.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            status("시험음 재생 실패");
        } finally {
            if (track != null) track.release();
        }
    }

    private static short[] toneSamples(int channel) {
        short[] samples = new short[OUTPUT_RATE * 2];
        int fade = OUTPUT_RATE / 40;
        for (int i = 0; i < OUTPUT_RATE; i++) {
            double envelope = Math.min(1.0, Math.min(i, OUTPUT_RATE - 1 - i) / (double) fade);
            double amplitude = Short.MAX_VALUE * 0.15 * envelope;
            if (channel != 2) samples[i * 2] = (short) (amplitude * Math.sin(2 * Math.PI * 440 * i / OUTPUT_RATE));
            if (channel != 1) samples[i * 2 + 1] = (short) (amplitude * Math.sin(2 * Math.PI * 660 * i / OUTPUT_RATE));
        }
        return samples;
    }

    private void readMicrophone() {
        AudioRecord record = null;
        try {
            int bufferSize = AudioRecord.getMinBufferSize(INPUT_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (bufferSize <= 0) {
                status("마이크 형식 초기화 실패");
                return;
            }
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, INPUT_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                status("마이크 초기화 실패");
                return;
            }
            if (Thread.currentThread() != worker) return;
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                status("마이크 입력 시작 실패");
                return;
            }
            status("마이크 입력 측정 중");
            short[] samples = new short[INPUT_RATE / 20];
            long deadline = SystemClock.elapsedRealtime() + 10000;
            boolean receivedSamples = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                int count = record.read(samples, 0, samples.length, AudioRecord.READ_NON_BLOCKING);
                if (count < 0) {
                    status("마이크 입력 읽기 실패");
                    return;
                }
                if (count > 0) {
                    receivedSamples = true;
                    double squares = 0;
                    for (int i = 0; i < count; i++) squares += (double) samples[i] * samples[i];
                    int percent = (int) Math.round(100 * Math.sqrt(squares / count) / 32768);
                    deliver(() -> listener.onLevel(percent));
                }
                Thread.sleep(45);
            }
            status(receivedSamples ? "마이크 입력 측정 종료" : "마이크 입력 데이터 없음");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (SecurityException e) {
            status("마이크 권한 없음");
        } catch (RuntimeException e) {
            status("마이크 입력 측정 실패");
        } finally {
            if (record != null) record.release();
        }
    }
}
