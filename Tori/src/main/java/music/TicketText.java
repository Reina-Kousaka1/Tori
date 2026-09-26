package music;

/** Guild-specific ticket copy. Defaults preserve panels and tickets created before customization. */
record TicketText(String panelTitle, String panelBody, String buttonLabel, String welcomeBody,
                  String claimLabel, String closeLabel, String deleteLabel) {
    static final String DEFAULT_TITLE = "🎫 TORI SUPPORT";
    static final String DEFAULT_BODY = "Need assistance? Open a private ticket below.";
    static final String DEFAULT_BUTTON = "Open Ticket";
    static final String DEFAULT_WELCOME = "Ticket #{ticket} · {category} · opened by {user}\nUse `/ticket close` when your request is resolved.";
    static final String DEFAULT_CLAIM = "Claim";
    static final String DEFAULT_CLOSE = "Close";
    static final String DEFAULT_DELETE = "Delete";

    static TicketText defaults() {
        return new TicketText(DEFAULT_TITLE, DEFAULT_BODY, DEFAULT_BUTTON, DEFAULT_WELCOME,
            DEFAULT_CLAIM, DEFAULT_CLOSE, DEFAULT_DELETE);
    }

    static TicketText from(TicketOrderStore.Config config) {
        return new TicketText(orDefault(config.panelTitle(), DEFAULT_TITLE), orDefault(config.panelBody(), DEFAULT_BODY),
            orDefault(config.buttonLabel(), DEFAULT_BUTTON), orDefault(config.welcomeBody(), DEFAULT_WELCOME),
            orDefault(config.claimLabel(), DEFAULT_CLAIM), orDefault(config.closeLabel(), DEFAULT_CLOSE),
            orDefault(config.deleteLabel(), DEFAULT_DELETE));
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static String validate(String value, String label, int maxLength) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty() || cleaned.length() > maxLength || cleaned.indexOf('\u0000') >= 0)
            throw new IllegalArgumentException(label + " must contain 1–" + maxLength + " characters.");
        if ((label.equals("Panel title") || label.endsWith("button")) && (cleaned.contains("\n") || cleaned.contains("\r")))
            throw new IllegalArgumentException(label + " must be a single line.");
        return cleaned;
    }

    String panelMessage() { return panelTitle + "\n\n" + panelBody; }

    static String preview(String value) {
        return value.length() <= 450 ? value : value.substring(0, 450) + "…";
    }

    String welcomeMessage(long ticketId, String category, String creatorId) {
        return welcomeBody.replace("{ticket}", Long.toString(ticketId)).replace("{category}", category)
            .replace("{user}", "<@" + creatorId + ">");
    }
}
