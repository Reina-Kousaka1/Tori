package music;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Resolves a fresh audio URL before playback without invoking a shell or downloading a file. */
final class YtDlp {
    interface Runner { String run(List<String> command) throws Exception; }
    private static final Semaphore SLOTS = new Semaphore(4);
    private final String executable;
    private final String runtime;
    private final Runner runner;
    YtDlp(String executable, String runtime, Runner runner) {
        this.executable = executable;
        this.runtime = runtime;
        this.runner = runner;
    }
    static YtDlp fromConfig(BotConfig config) {
        var defaults = defaults();
        return new YtDlp(config.get("YTDLP_PATH", defaults.executable),
            config.get("YTDLP_JS_RUNTIME", defaults.runtime), YtDlp::run);
    }
    static YtDlp defaults() {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return new YtDlp(windows ? "tools/yt-dlp.exe" : "yt-dlp",
            windows ? "node:tools/node.exe" : "node", YtDlp::run);
    }
    String streamUrl(String pageUrl) {
        if (!trustedUrl(pageUrl, List.of("youtube.com", "youtu.be", "soundcloud.com")))
            throw new UserError("input.source");
        if (!SLOTS.tryAcquire()) throw new UserError("busy");
        try {
            var command = new ArrayList<>(List.of(executable, "--ignore-config", "--no-cache-dir",
                "--no-playlist", "--no-warnings", "--skip-download", "--socket-timeout", "15",
                "--retries", "1", "--extractor-retries", "1", "--format", "bestaudio/best", "--get-url"));
            if (runtime != null && !runtime.isBlank()) command.addAll(List.of("--js-runtimes", runtime));
            command.addAll(List.of("--", pageUrl));
            String result = runner.run(List.copyOf(command)).strip();
            if (!trustedUrl(result, List.of("googlevideo.com", "sndcdn.com", "soundcloud.com")))
                throw new UserError("youtube.extract.failed");
            return result;
        } catch (UserError ex) { throw ex; }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UserError("shutting.down");
        } catch (TimeoutException ex) { throw new UserError("youtube.extract.timeout"); }
        catch (IOException ex) { throw new UserError("youtube.tool.missing"); }
        catch (Exception ex) { throw new UserError("youtube.extract.failed"); }
        finally { SLOTS.release(); }
    }
    private static boolean trustedUrl(String value, List<String> domains) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1
                && domains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
        } catch (IllegalArgumentException | NullPointerException ex) { return false; }
    }
    private static String run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var output = new FutureTask<>(() -> process.getInputStream().readNBytes(16_385));
        Thread.ofVirtual().name("yt-dlp-output").start(output);
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) throw new TimeoutException();
            byte[] bytes = output.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || bytes.length > 16_384) throw new UserError("youtube.extract.failed");
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close();
            output.cancel(true);
        }
    }
}
