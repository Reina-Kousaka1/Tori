package music;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Existing quick-job rewards; these are separate from fishing/mining tool drops. */
final class WorkCatalog {
    record Job(String id, String name, long minimum, long maximum) {}
    record Result(String jobId, String jobName, long amount, long remaining) {}

    private static final Map<String, Job> JOBS = Map.of(
        "mine", new Job("mine", "Mine", 45, 120),
        "fish", new Job("fish", "Fish", 35, 100),
        "chop", new Job("chop", "Chop", 30, 90));

    private WorkCatalog() {}

    static List<Job> jobs() {
        return JOBS.values().stream().sorted(java.util.Comparator.comparing(Job::id)).toList();
    }

    static Job find(String id) {
        return id == null ? null : JOBS.get(id.strip().toLowerCase(Locale.ROOT));
    }

    static long reward(Job job) {
        return ThreadLocalRandom.current().nextLong(job.minimum(), Math.addExact(job.maximum(), 1));
    }
}
