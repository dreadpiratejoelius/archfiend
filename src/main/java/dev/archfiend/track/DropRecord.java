package dev.archfiend.track;

import dev.archfiend.drop.DropTier;
import dev.archfiend.drop.RngDrop;
import org.jspecify.annotations.Nullable;

/**
 * One recognised drop, as persisted in {@code drops.json}. Mutable only while it waits for
 * confirmation; plain fields keep Gson serialisation trivial.
 */
public final class DropRecord
{
    public long time;
    public DropTier tier;
    public String label;
    public int labelColor;
    public String item;
    public int itemColor;
    public @Nullable Integer magicFind;
    /** SkyBlock id, from the arrived item or the repo match. */
    public @Nullable String skyblockId;
    /** {@code "Inventory"}, a sack name, or {@code null} if the item was never seen arriving. */
    public @Nullable String destination;

    /** Gson. */
    @SuppressWarnings("unused")
    private DropRecord() {}

    public DropRecord(RngDrop drop, @Nullable String skyblockId, long time)
    {
        this.time = time;
        this.tier = drop.tier();
        this.label = drop.label();
        this.labelColor = drop.labelColor();
        this.item = drop.itemName();
        this.itemColor = drop.itemColor();
        this.magicFind = drop.magicFind();
        this.skyblockId = skyblockId;
    }

    public boolean confirmed()
    {
        return destination != null;
    }

    boolean valid()
    {
        return tier != null && item != null && !item.isBlank() && label != null;
    }
}
