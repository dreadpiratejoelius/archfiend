package dev.archfiend.drop;

import dev.archfiend.config.ArchfiendConfig.Trigger;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises SkyBlock drop announcements such as
 * {@code CRAZY RARE DROP! (Warden Heart) (+312% ✯ Magic Find)}.
 */
public final class DropParser
{
    /** Trailing magic find suffix, e.g. {@code (+312% ✯ Magic Find)}. */
    private static final Pattern MAGIC_FIND_SUFFIX = Pattern.compile("\\s*\\(\\+([\\d,.]+)%\\s*✯?\\s*Magic Find\\)\\s*$");
    private static final int MAX_ITEM_NAME_LENGTH = 64;

    private final List<Trigger> triggers;

    public DropParser(List<Trigger> triggers)
    {
        // Longest prefix first so "VERY RARE DROP!" can never be shadowed by a shorter overlapping prefix.
        this.triggers = triggers.stream()
            .sorted(Comparator.comparingInt((Trigger t) -> t.prefix().length()).reversed())
            .toList();
    }

    public Optional<RngDrop> parse(Component message)
    {
        StyledText styled = StyledText.of(message);
        String plain = styled.plain();

        int start = skipWhitespace(plain, 0);
        for (Trigger trigger : triggers)
        {
            if (plain.startsWith(trigger.prefix(), start))
            {
                return parseItem(styled, start, trigger, message);
            }
        }
        return Optional.empty();
    }

    private static Optional<RngDrop> parseItem(StyledText styled, int labelStart, Trigger trigger, Component message)
    {
        String plain = styled.plain();
        int labelEnd = labelStart + trigger.prefix().length();

        int itemStart = skipWhitespace(plain, labelEnd);
        int itemEnd = plain.length();

        Integer magicFind = null;
        Matcher suffix = MAGIC_FIND_SUFFIX.matcher(plain);
        if (suffix.find(itemStart))
        {
            itemEnd = suffix.start();
            magicFind = parseMagicFind(suffix.group(1));
        }
        while (itemEnd > itemStart && Character.isWhitespace(plain.charAt(itemEnd - 1))) itemEnd--;

        // Slayer drops wrap the item in parentheses: "VERY RARE DROP! (Foul Flesh)".
        if (itemEnd - itemStart >= 2 && plain.charAt(itemStart) == '(' && plain.charAt(itemEnd - 1) == ')')
        {
            itemStart = skipWhitespace(plain, itemStart + 1);
            itemEnd--;
            while (itemEnd > itemStart && Character.isWhitespace(plain.charAt(itemEnd - 1))) itemEnd--;
        }

        if (itemEnd <= itemStart || itemEnd - itemStart > MAX_ITEM_NAME_LENGTH) return Optional.empty();

        String label = plain.substring(labelStart, labelEnd);
        String itemName = plain.substring(itemStart, itemEnd);
        int labelColor = styled.colorAt(labelStart);
        int itemColor = styled.colorAt(itemStart);

        return Optional.of(new RngDrop(trigger.tier(), label, labelColor, itemName, itemColor, magicFind, message));
    }

    private static @Nullable Integer parseMagicFind(String raw)
    {
        try
        {
            return (int) Math.round(Double.parseDouble(raw.replace(",", "")));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static int skipWhitespace(String s, int from)
    {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }
}
