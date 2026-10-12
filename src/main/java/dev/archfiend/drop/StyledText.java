package dev.archfiend.drop;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.Arrays;
import java.util.Optional;

/**
 * A flattened chat component: the plain text plus the RGB colour of every character.
 * <p>
 * Hypixel mixes component styles with legacy {@code §} codes embedded in literal text, so both
 * sources are resolved here. Legacy codes are removed from {@link #plain()}.
 */
public record StyledText(String plain, int[] colors)
{
    private static final int DEFAULT_COLOR = 0xFFFFFF;

    public static StyledText of(Component component)
    {
        StringBuilder text = new StringBuilder();
        IntBuffer colors = new IntBuffer();

        component.visit((style, segment) -> {
            int baseColor = colorOf(style);
            int color = baseColor;

            for (int i = 0; i < segment.length(); i++)
            {
                char c = segment.charAt(i);
                if (c == ChatFormatting.PREFIX_CODE && i + 1 < segment.length())
                {
                    ChatFormatting formatting = ChatFormatting.getByCode(Character.toLowerCase(segment.charAt(++i)));
                    if (formatting == ChatFormatting.RESET)
                    {
                        color = baseColor;
                    }
                    else if (formatting != null)
                    {
                        TextColor legacyColor = TextColor.fromLegacyFormat(formatting);
                        if (legacyColor != null) color = legacyColor.getValue();
                    }
                    continue;
                }
                text.append(c);
                colors.add(color);
            }
            return Optional.empty();
        }, Style.EMPTY);

        return new StyledText(text.toString(), colors.toArray());
    }

    /** Colour of the character at {@code index}, or white when out of range. */
    public int colorAt(int index)
    {
        return index >= 0 && index < colors.length ? colors[index] : DEFAULT_COLOR;
    }

    private static int colorOf(Style style)
    {
        TextColor color = style.getColor();
        return color != null ? color.getValue() : DEFAULT_COLOR;
    }

    private static final class IntBuffer
    {
        private int[] data = new int[64];
        private int size;

        void add(int value)
        {
            if (size == data.length) data = Arrays.copyOf(data, size * 2);
            data[size++] = value;
        }

        int[] toArray()
        {
            return Arrays.copyOf(data, size);
        }
    }

    @Override
    public boolean equals(Object o)
    {
        return o instanceof StyledText(String otherPlain, int[] otherColors)
            && plain.equals(otherPlain) && Arrays.equals(colors, otherColors);
    }

    @Override
    public int hashCode()
    {
        return 31 * plain.hashCode() + Arrays.hashCode(colors);
    }

    @Override
    public String toString()
    {
        return "StyledText[" + plain + "]";
    }
}
