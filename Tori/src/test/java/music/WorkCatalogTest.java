package music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkCatalogTest {
    @Test void exposesOnlyTheExistingJobsAndTheirRewardBounds() {
        assertEquals(java.util.List.of("chop", "fish", "mine"), WorkCatalog.jobs().stream().map(WorkCatalog.Job::id).toList());
        assertEquals(new WorkCatalog.Job("fish", "Fish", 35, 100), WorkCatalog.find(" FISH "));
        assertNull(WorkCatalog.find("barista"), "do not advertise a job absent from the reference catalog");
        for (var job : WorkCatalog.jobs()) {
            for (int i = 0; i < 100; i++) {
                long reward = WorkCatalog.reward(job);
                assertTrue(reward >= job.minimum() && reward <= job.maximum());
            }
        }
    }
}
