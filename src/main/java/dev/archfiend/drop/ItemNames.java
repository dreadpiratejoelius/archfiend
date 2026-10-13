package dev.archfiend.drop;

import net.minecraft.ChatFormatting;

import java.util.Locale;
import java.util.regex.Pattern;

/** Canonical form of SkyBlock item names so chat, inventory and repo names compare equal. */
public final class ItemNames
{
    /** Pet level prefix, both live ({@code [Lvl 100] }) and the repo template ({@code [Lvl {LVL}] }). */
    public static final Pattern PET_LEVEL_PREFIX = Pattern.compile("^\\[Lvl [^\\]]*]\\s*");
    private static final Pattern COUNT_SUFFIX = Pattern.compile("\\s+x[\\d,]+$");
    private static final Pattern RUNE_SYMBOL = Pattern.compile("[◆◇]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern NON_ID_CHARS = Pattern.compile("[^a-z0-9_]");

    private ItemNames() {}

    /** Strips formatting, pet level prefixes, rune symbols and stack counts; lower-cases and collapses whitespace. */
    public static String normalize(String name)
    {
        if (name == null) return "";
        String stripped = ChatFormatting.stripFormatting(name);
        if (stripped == null) return "";
        stripped = RUNE_SYMBOL.matcher(stripped).replaceAll("");
        String result = WHITESPACE.matcher(stripped.trim()).replaceAll(" ");
        result = PET_LEVEL_PREFIX.matcher(result).replaceFirst("");
        result = COUNT_SUFFIX.matcher(result).replaceFirst("");
        return result.toLowerCase(Locale.ROOT);
    }

    /** Resource-path form of a name: {@code Necron's Handle} becomes {@code necrons_handle}. */
    public static String toId(String name)
    {
        return NON_ID_CHARS.matcher(normalize(name).replace(' ', '_')).replaceAll("");
    }
}
