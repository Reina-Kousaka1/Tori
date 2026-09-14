package music;

import java.util.*;

/** Confined to a guild's serial worker. */
public final class TrackQueue<T> {
    private final int capacity;
    private final Deque<T> waiting = new ArrayDeque<>();
    private T current;
    private boolean repeat;
    public boolean repeating() { return repeat; }
    public void repeat(boolean enabled) { repeat = enabled; }
    public T advance(boolean naturalEnd) { return naturalEnd && repeat && current != null ? current : advance(); }
    public TrackQueue() { this(200); }
    public TrackQueue(int capacity) { this.capacity = capacity; }
    public T current() { return current; }
    public void add(List<T> tracks) {
        if (waiting.size() + tracks.size() > capacity)
            throw new UserError("queue.full", capacity);
        waiting.addAll(tracks);
    }
    public T advance() { current = waiting.pollFirst(); return current; }
    public void finishCurrent() { current = null; }
    public List<T> snapshot() { return List.copyOf(waiting); }
    public void clear() { waiting.clear(); current = null; repeat = false; }
}
