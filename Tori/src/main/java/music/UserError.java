package music;

/** A safe, translatable error; external exception messages are never shown to users. */
public final class UserError extends IllegalArgumentException {
    private final String key;
    private final Object[] args;
    public UserError(String key, Object... args) { super(key); this.key = key; this.args = args.clone(); }
    public String localized(Language language) { return Messages.text(language, key, args); }
}
