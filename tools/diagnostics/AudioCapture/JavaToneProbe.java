import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/** Explicit, low-amplitude generated tone control; never reads microphone data. */
public final class JavaToneProbe {
    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("btw.qa.captureProbe") || args.length != 1)
            throw new IllegalArgumentException("Use -Dbtw.qa.captureProbe=true and one capture readiness filename");
        Path ready = Path.of(args[0]).toAbsolutePath().normalize();
        long deadline = System.nanoTime() + 20_000_000_000L;
        System.out.println("WAITING_FOR_QA_CAPTURE_READY");
        while (!Files.isRegularFile(ready)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("No capture readiness within 20 seconds");
            Thread.sleep(20);
        }
        int rate = 44100;
        AudioFormat format = new AudioFormat(rate, 16, 2, true, false);
        byte[] pcm = new byte[rate * 4];
        for (int frame = 0; frame < rate; frame++) {
            double envelope = Math.min(1, Math.min(frame / 441.0, (rate - frame - 1) / 441.0));
            short sample = (short)Math.round(Math.sin(2 * Math.PI * 440 * frame / rate) * 1638 * envelope);
            for (int channel = 0; channel < 2; channel++) {
                int offset = frame * 4 + channel * 2;
                pcm[offset] = (byte)sample;
                pcm[offset + 1] = (byte)(sample >> 8);
            }
        }
        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format);
            line.start();
            System.out.println("EMITTING_ONE_SECOND_GENERATED_QA_TONE");
            line.write(pcm, 0, pcm.length);
            line.drain();
            line.stop();
        }
        System.out.println("QA_TONE_FINISHED");
    }
}
