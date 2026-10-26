package music;

/** Discord wording for domain results; it cannot change action or cooldown outcomes. */
final class SocialActionPresenter {
    private SocialActionPresenter() {}

    static String success(Language language, EconomyV2Client.SocialActionResult result) {
        String verb = Messages.text(language, "social.verb." + result.actionId());
        return Messages.text(language, "social.action.success",
            "<@" + result.actorUserId() + ">", verb, "<@" + result.targetUserId() + ">");
    }

    static String cooldown(Language language, long remainingMillis) {
        return Messages.text(language, "social.action.cooldown", SocialCooldownFormatter.format(remainingMillis));
    }
}
