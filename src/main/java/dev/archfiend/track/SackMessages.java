package dev.archfiend.track;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Hypixel's sack summaries. Items that go straight to a sack never touch the inventory;
 * Hypixel reports them every few seconds as {@code [Sacks] +3 items. (Last 5s.)} with the details
 * in the hover text, one line per item: {@code +1 Warden Heart (Combat Sack)}.
 */
public final class SackMessages
{
    private static final String PREFIX = "[Sacks]";
    private static final Pattern ADDED_LINE = Pattern.compile("^\\s*\\+([\\d,]+)\\s+(.+?)\\s*\\((.+?)\\)\\s*$");

    private SackMessages() {}

    /** @return the items added to sacks, or an empty list if this is not a sack summary */
    public static List<ItemGain> parse(Component message, long atMillis)
    {
        String plain = ChatFormatting.stripFormatting(message.getString());
        if (plain == null || !plain.trim().startsWith(PREFIX)) return List.of();

        List<ItemGain> gains = new ArrayList<>();
        message.visit((style, text) -> {
            collect(style, atMillis, gains);
            return Optional.empty();
        }, Style.EMPTY);
        return gains;
    }

    private static void collect(Style style, long atMillis, List<ItemGain> gains)
    {
        if (!(style.getHoverEvent() instanceof HoverEvent.ShowText(Component hover))) return;
        String text = ChatFormatting.stripFormatting(hover.getString());
        if (text == null) return;

        for (String line : text.split("\n"))
        {
            Matcher matcher = ADDED_LINE.matcher(line);
            if (!matcher.matches()) continue;
            int count = Integer.parseInt(matcher.group(1).replace(",", ""));
            ItemGain gain = new ItemGain(matcher.group(2).trim(), null, count, null, matcher.group(3).trim(), atMillis);
            // A summary's hover can be attached to several segments; keep each item once.
            if (!gains.contains(gain)) gains.add(gain);
        }
    }
}
