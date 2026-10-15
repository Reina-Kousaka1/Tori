package music;

import java.net.URI;
import java.util.Optional;

/** Replaceable Discord presentation source for action GIFs. It has no role in social decisions. */
@FunctionalInterface
interface SocialGifProvider {
    Optional<URI> select(String actionId, String interactionId);
}
