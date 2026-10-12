package dev.archfiend.repo;

import java.util.List;

/**
 * A mob's loot table from a NEU {@code "type": "drops"} recipe.
 *
 * @param source plain name of the mob, e.g. {@code Revenant Horror V}
 */
public record DropTable(String source, List<Entry> entries)
{
    /**
     * @param internalName dropped item id
     * @param chance       drop chance in percent (0–100]
     */
    public record Entry(String internalName, double chance) {}

    public DropTable
    {
        entries = List.copyOf(entries);
    }

    public boolean contains(String internalName)
    {
        for (Entry entry : entries)
        {
            if (entry.internalName().equals(internalName)) return true;
        }
        return false;
    }
}
