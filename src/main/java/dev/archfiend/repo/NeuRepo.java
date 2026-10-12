package dev.archfiend.repo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.archfiend.ArchfiendClient;
import dev.archfiend.drop.ItemNames;
import dev.archfiend.drop.StyledText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Immutable, in-memory index of the NEU item repo: items by id and by name, plus mob loot tables.
 * <p>
 * Loaded straight from the downloaded GitHub archive without extracting it. The archive is
 * untrusted input, so every entry is size-capped and parsed defensively; a bad file is skipped.
 */
public final class NeuRepo
{
    /** GitHub archives nest everything under {@code <repo>-<ref>/}. */
    private static final Pattern ITEM_ENTRY = Pattern.compile("^[^/]+/items/[^/]+\\.json$");
    private static final long MAX_ENTRY_BYTES = 4L * 1024 * 1024;

    private static final Pattern ITEM_MODEL = Pattern.compile("ItemModel:\"([a-z0-9_.-]+:[a-z0-9_./-]+)\"");
    private static final Pattern SKULL_TEXTURE = Pattern.compile("textures:\\[[^\\]]*?Value:\"([A-Za-z0-9+/=]+)\"");
    private static final Pattern SKULL_OWNER = Pattern.compile("SkullOwner:\\{Id:\"([0-9a-fA-F-]{36})\"");
    private static final Pattern DYE_COLOR = Pattern.compile("\\bcolor:(-?\\d+)");
    private static final Pattern ENCHANTED = Pattern.compile("\\bench:\\[");
    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*%");
    private static final Pattern ONE_IN = Pattern.compile("1\\s*/\\s*([\\d,]+)");
    private static final double DEFAULT_CHANCE = 1.0;

    private final Map<String, NeuItem> byId;
    private final Map<String, List<NeuItem>> byName;
    private final Map<String, List<DropTable>> tablesByItem;

    private NeuRepo(Map<String, NeuItem> byId, Map<String, List<NeuItem>> byName, Map<String, List<DropTable>> tablesByItem)
    {
        this.byId = Map.copyOf(byId);
        this.byName = Map.copyOf(byName);
        this.tablesByItem = Map.copyOf(tablesByItem);
    }

    public static NeuRepo load(Path archive) throws IOException
    {
        Builder builder = new Builder();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8))
        {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements())
            {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !ITEM_ENTRY.matcher(entry.getName()).matches()) continue;
                if (entry.getSize() > MAX_ENTRY_BYTES)
                {
                    ArchfiendClient.LOGGER.warn("Skipping oversized repo entry {}", entry.getName());
                    continue;
                }
                try (InputStream in = new BoundedInputStream(zip.getInputStream(entry), MAX_ENTRY_BYTES);
                     Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
                {
                    builder.accept(JsonParser.parseReader(reader));
                }
                catch (JsonParseException | IllegalStateException | IOException e)
                {
                    ArchfiendClient.LOGGER.debug("Skipping unreadable repo entry {}: {}", entry.getName(), e.toString());
                }
            }
        }
        return builder.build();
    }

    public int itemCount()
    {
        return byId.size();
    }

    /** Visits every item id; for diagnostics and tests. */
    void forEachItemId(Consumer<String> action)
    {
        byId.keySet().forEach(action);
    }

    public Optional<NeuItem> item(String internalName)
    {
        return Optional.ofNullable(byId.get(internalName));
    }

    /**
     * Finds an item by display name. Names are not unique (pets exist once per rarity), so the
     * candidate whose name colour matches {@code preferredColor} wins.
     */
    public Optional<NeuItem> findByName(String name, int preferredColor)
    {
        List<NeuItem> candidates = byName.get(ItemNames.normalize(name));
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        for (NeuItem candidate : candidates)
        {
            if (candidate.nameColor() == preferredColor) return Optional.of(candidate);
        }
        return Optional.of(candidates.getFirst());
    }

    /** The richest loot table that can drop the given item, if any mob is known to drop it. */
    public Optional<DropTable> lootTableFor(String internalName)
    {
        List<DropTable> tables = tablesByItem.get(internalName);
        if (tables == null) return Optional.empty();
        return tables.stream().max(Comparator.comparingInt(table -> table.entries().size()));
    }

    /** Parses NEU chance strings such as {@code 0.0136%}, {@code §a5%} or {@code 1/500}. */
    static double parseChance(@Nullable String raw)
    {
        if (raw == null) return DEFAULT_CHANCE;
        String text = ChatFormatting.stripFormatting(raw);
        if (text == null) return DEFAULT_CHANCE;

        Matcher percent = PERCENT.matcher(text);
        if (percent.find()) return clampChance(Double.parseDouble(percent.group(1).replace(',', '.')));

        Matcher oneIn = ONE_IN.matcher(text);
        if (oneIn.find())
        {
            double denominator = Double.parseDouble(oneIn.group(1).replace(",", ""));
            if (denominator > 0) return clampChance(100.0 / denominator);
        }
        return DEFAULT_CHANCE;
    }

    private static double clampChance(double chance)
    {
        if (!Double.isFinite(chance) || chance <= 0) return DEFAULT_CHANCE;
        return Math.min(chance, 100.0);
    }

    private static final class Builder
    {
        private final Map<String, NeuItem> byId = new HashMap<>();
        private final Map<String, List<NeuItem>> byName = new HashMap<>();
        private final Map<String, List<DropTable>> tablesByItem = new HashMap<>();

        void accept(JsonElement element)
        {
            if (!(element instanceof JsonObject json)) return;
            String internalName = string(json, "internalname");
            String displayName = string(json, "displayname");
            String itemId = string(json, "itemid");
            if (internalName == null || displayName == null || itemId == null) return;

            String nbt = string(json, "nbttag");
            if (nbt == null) nbt = "";

            StyledText styled = StyledText.of(Component.literal(displayName));
            Matcher petPrefix = ItemNames.PET_LEVEL_PREFIX.matcher(styled.plain());
            int nameStart = petPrefix.find() ? petPrefix.end() : 0;
            String name = styled.plain().substring(nameStart).trim();
            if (name.isEmpty()) return;

            NeuItem item = new NeuItem(
                internalName,
                name,
                styled.colorAt(nameStart),
                itemId,
                json.has("damage") && json.get("damage").isJsonPrimitive() ? json.get("damage").getAsInt() : 0,
                group(ITEM_MODEL, nbt),
                group(SKULL_TEXTURE, nbt),
                group(SKULL_OWNER, nbt),
                itemId.contains("leather_") ? parseColor(group(DYE_COLOR, nbt)) : null,
                ENCHANTED.matcher(nbt).find()
            );
            byId.put(internalName, item);
            byName.computeIfAbsent(ItemNames.normalize(name), key -> new ArrayList<>()).add(item);

            acceptRecipes(json, name);
        }

        private void acceptRecipes(JsonObject json, String fallbackSource)
        {
            if (!(json.get("recipes") instanceof JsonArray recipes)) return;
            for (JsonElement recipeElement : recipes)
            {
                if (!(recipeElement instanceof JsonObject recipe) || !"drops".equals(string(recipe, "type"))) continue;
                if (!(recipe.get("drops") instanceof JsonArray drops)) continue;

                List<DropTable.Entry> entries = new ArrayList<>();
                for (JsonElement dropElement : drops)
                {
                    if (!(dropElement instanceof JsonObject drop)) continue;
                    String id = string(drop, "id");
                    if (id != null && !id.isBlank()) entries.add(new DropTable.Entry(id, parseChance(string(drop, "chance"))));
                }
                if (entries.isEmpty()) continue;

                String source = string(recipe, "name");
                String plainSource = source != null ? ChatFormatting.stripFormatting(source) : null;
                DropTable table = new DropTable(plainSource != null && !plainSource.isBlank() ? plainSource : fallbackSource, entries);
                for (DropTable.Entry entry : table.entries())
                {
                    List<DropTable> tables = tablesByItem.computeIfAbsent(entry.internalName(), key -> new ArrayList<>());
                    if (!tables.contains(table)) tables.add(table);
                }
            }
        }

        NeuRepo build()
        {
            // Deterministic candidate order regardless of zip entry order.
            byName.values().forEach(list -> list.sort(Comparator.comparing(NeuItem::internalName)));
            return new NeuRepo(byId, byName, tablesByItem);
        }

        private static @Nullable String string(JsonObject json, String key)
        {
            JsonElement element = json.get(key);
            return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
        }

        private static @Nullable String group(Pattern pattern, String input)
        {
            Matcher matcher = pattern.matcher(input);
            return matcher.find() ? matcher.group(1) : null;
        }

        private static @Nullable Integer parseColor(@Nullable String raw)
        {
            if (raw == null) return null;
            try
            {
                return Integer.parseInt(raw) & 0xFFFFFF;
            }
            catch (NumberFormatException e)
            {
                return null;
            }
        }
    }

    /** Guards against zip bombs: entries whose declared size lies are cut off at the cap. */
    private static final class BoundedInputStream extends InputStream
    {
        private final InputStream delegate;
        private long remaining;

        BoundedInputStream(InputStream delegate, long limit)
        {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException
        {
            if (remaining <= 0) throw new IOException("Repo entry exceeds size limit");
            int b = delegate.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException
        {
            if (remaining <= 0) throw new IOException("Repo entry exceeds size limit");
            int read = delegate.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) remaining -= read;
            return read;
        }

        @Override
        public void close() throws IOException
        {
            delegate.close();
        }
    }
}
