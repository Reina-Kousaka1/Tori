package music;

public final class ModerationPolicy {
    private ModerationPolicy() {}
    public static void target(long actor, long target, long bot, boolean owner, boolean administrator,
                              boolean actorAbove, boolean botAbove, boolean timeout) {
        if (target == actor) throw new UserError("mod.self");
        if (target == bot) throw new UserError("mod.bot.self");
        if (owner) throw new UserError("mod.owner");
        if (!actorAbove) throw new UserError("mod.actor.role");
        if (!botAbove) throw new UserError("mod.bot.role");
        if (timeout && administrator) throw new UserError("mod.admin.timeout");
    }
}
