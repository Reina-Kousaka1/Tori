package music;

import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import java.util.List;

/** Discord-only market pagination; prices and product rules remain in the existing store. */
final class ToriShopUi {
    static final int PAGE_SIZE = 5;
    record Page(int index, int count, int from, int to) {}
    record Entry(String id, String name, String price) {}
    private ToriShopUi() {}

    static Page page(int total, int requested) {
        if (total < 0) throw new IllegalArgumentException("total");
        int count = (int) Math.max(1, ((long) total + PAGE_SIZE - 1) / PAGE_SIZE);
        int index = Math.max(0, Math.min(requested, count - 1));
        int from = index * PAGE_SIZE;
        return new Page(index, count, from, (int) Math.min(total, (long) from + PAGE_SIZE));
    }

    static MessageEmbed render(Language language, List<Entry> entries, Page page, String category) {
        return render(language, entries, page, category, "");
    }

    static MessageEmbed render(Language language, List<Entry> entries, Page page, String category, String note) {
        var embed = ToriEmbeds.create(ToriEmbeds.Category.SHOP, language)
            .setTitle(Messages.text(language, "shop.current.title"))
            .setDescription(Messages.text(language, "shop.ui.category", category)
                + "\n" + Messages.text(language, "shop.ui.page", page.index() + 1, page.count())
                + (note.isBlank() ? "" : "\n\n" + ToriEmbeds.shorten(note, 600)));
        if (entries.isEmpty()) {
            embed.addField(Messages.text(language, "shop.ui.empty.title"),
                Messages.text(language, "market.products.empty"), false);
        } else for (Entry entry : entries) {
            embed.addField(ToriEmbeds.shorten(entry.name(), 256),
                "`" + entry.id() + "`\n" + ToriEmbeds.shorten(entry.price(), 200), false);
        }
        return embed.setFooter(ToriEmbeds.footer(language, Messages.text(language, "shop.current.footer"))).build();
    }

    static List<ActionRow> controls(String userId, String category, Page page, Language language) {
        String base = "tori:s:" + userId + ":" + category + ":";
        return List.of(ActionRow.of(
            Button.secondary(base + (page.index() - 1), Messages.text(language, "shop.ui.previous"))
                .withDisabled(page.index() == 0),
            Button.secondary(base + page.index(), Messages.text(language, "shop.ui.page", page.index() + 1, page.count()))
                .withDisabled(true),
            Button.secondary(base + (page.index() + 1), Messages.text(language, "shop.ui.next"))
                .withDisabled(page.index() + 1 >= page.count())));
    }
}
