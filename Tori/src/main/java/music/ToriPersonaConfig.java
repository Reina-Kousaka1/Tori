package music;

/** Persona presentation switches; these settings never influence shop prices or economy rules. */
record ToriPersonaConfig(boolean enabled, int pickMeIntensity) {
    static ToriPersonaConfig load(BotConfig config) {
        return new ToriPersonaConfig(
            Boolean.parseBoolean(config.get("TORI_PERSONA_ENABLED", "true")),
            parseIntensity(config.get("TORI_PICK_ME_INTENSITY", "72")));
    }

    static int parseIntensity(String value) {
        try { return Math.clamp(Integer.parseInt(value.strip()), 0, 100); }
        catch (RuntimeException ignored) { return 72; }
    }
}
