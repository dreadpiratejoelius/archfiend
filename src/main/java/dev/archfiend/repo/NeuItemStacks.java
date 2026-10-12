package dev.archfiend.repo;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.MissingItemModel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ResolvableProfile;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Builds renderable {@link ItemStack}s from repo items.
 * <p>
 * Resolution order for the base item: the repo's modern {@code ItemModel} when it is a vanilla
 * item, otherwise the 1.8 id mapped through {@link LegacyItems}. Hypixel's own resource-pack models
 * ({@code hypixel_skyblock:...}) are applied on top only when that pack is actually loaded, so the
 * item never renders as the missing-model cube.
 */
public final class NeuItemStacks
{
    private static final String MINECRAFT = "minecraft";
    private static final String PROFILE_NAME = "archfiend";

    private final Predicate<Identifier> customModelAvailable;

    public NeuItemStacks(Predicate<Identifier> customModelAvailable)
    {
        this.customModelAvailable = customModelAvailable;
    }

    /** Uses the live client model manager; must be called on the client thread. */
    public static NeuItemStacks forClient()
    {
        return new NeuItemStacks(id -> !(Minecraft.getInstance().getModelManager().getItemModel(id) instanceof MissingItemModel));
    }

    public Optional<ItemStack> create(NeuItem item)
    {
        Item base = resolveBase(item);
        if (base == null) return Optional.empty();

        ItemStack stack = new ItemStack(base);

        Identifier model = item.itemModel() != null ? Identifier.tryParse(item.itemModel()) : null;
        if (model != null && !MINECRAFT.equals(model.getNamespace()) && customModelAvailable.test(model))
        {
            stack.set(DataComponents.ITEM_MODEL, model);
        }
        if (base == Items.PLAYER_HEAD && item.skullTexture() != null)
        {
            stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile(item.skullTexture(), item.skullOwner())));
        }
        if (item.dyeColor() != null)
        {
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(item.dyeColor()));
        }
        if (item.glint())
        {
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return Optional.of(stack);
    }

    static @Nullable Item resolveBase(NeuItem item)
    {
        Identifier model = item.itemModel() != null ? Identifier.tryParse(item.itemModel()) : null;
        if (model != null && MINECRAFT.equals(model.getNamespace()))
        {
            Item modern = lookup(model);
            if (modern != null) return modern;
        }
        Identifier legacy = Identifier.tryParse(MINECRAFT + ":" + LegacyItems.toModernPath(item.legacyItemId(), item.damage()));
        return legacy != null ? lookup(legacy) : null;
    }

    private static @Nullable Item lookup(Identifier id)
    {
        return BuiltInRegistries.ITEM.getOptional(id).filter(found -> found != Items.AIR).orElse(null);
    }

    private static GameProfile profile(String texture, @Nullable String owner)
    {
        UUID id = parseUuid(owner);
        if (id == null) id = UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8));
        PropertyMap properties = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", texture)));
        return new GameProfile(id, PROFILE_NAME, properties);
    }

    private static @Nullable UUID parseUuid(@Nullable String raw)
    {
        if (raw == null) return null;
        try
        {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }
}
