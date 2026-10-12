package dev.archfiend.repo;

import org.jspecify.annotations.Nullable;

/**
 * The parts of a NEU repo item needed to render it. Pure data, safe to build off-thread.
 *
 * @param internalName SkyBlock id, e.g. {@code WARDEN_HEART} or {@code SCATHA;4}
 * @param name         plain display name with pet level prefix removed, e.g. {@code Warden Heart}
 * @param nameColor    RGB colour of the name (its rarity colour)
 * @param legacyItemId 1.8 item id, e.g. {@code minecraft:skull}
 * @param damage       1.8 damage/metadata value that selects legacy variants
 * @param itemModel    modern item model id if the repo provides one, e.g. {@code minecraft:player_head}
 * @param skullTexture base64 skin texture for player heads
 * @param skullOwner   profile UUID string accompanying the texture
 * @param dyeColor     RGB leather dye colour
 * @param glint        whether the item is enchanted
 */
public record NeuItem(
    String internalName,
    String name,
    int nameColor,
    String legacyItemId,
    int damage,
    @Nullable String itemModel,
    @Nullable String skullTexture,
    @Nullable String skullOwner,
    @Nullable Integer dyeColor,
    boolean glint
) {}
