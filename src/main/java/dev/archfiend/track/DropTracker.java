package dev.archfiend.track;

import dev.archfiend.drop.ItemNames;
import dev.archfiend.drop.RngDrop;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Confirms chat-announced drops against what actually arrives: inventory gains (with the item's
 * SkyBlock id) and sack summaries. Every recognised drop is written to the {@link DropLog} straight
 * away and updated once its arrival is seen. Client thread only.
 */
public final class DropTracker
{
    /** Hypixel sometimes delivers the item a moment before the chat line. */
    private static final long ARRIVED_BEFORE_MS = 3_000;
    /** Sack summaries are batched every few seconds, so allow a generous wait. */
    private static final long ARRIVES_WITHIN_MS = 15_000;
    private static final int RECENT_GAINS = 40;
    private static final int LOG_LINES = 30;
    /** Hypixel gives every pet item the generic SkyBlock id {@code PET}; pets must be matched by name. */
    private static final String PET_ID = "PET";

    private final InventoryWatcher watcher = new InventoryWatcher();
    private final DropLog log;
    private final Predicate<DropRecord> canGoInSack;
    /** Arrivals not yet claimed by a drop; an item can confirm at most one drop. */
    private final Deque<ItemGain> unclaimed = new ArrayDeque<>();
    /** Every arrival, for {@code /archfiend log}. */
    private final Deque<ItemGain> arrivals = new ArrayDeque<>();
    private final List<Pending> pending = new ArrayList<>();
    private final Deque<String> decisions = new ArrayDeque<>();
    private final List<Consumer<Confirmation>> listeners = new ArrayList<>();

    /** A drop was matched to the item that arrived. */
    public record Confirmation(DropRecord record, ItemGain gain) {}

    private record Pending(DropRecord record, String normalizedName, long sinceMillis) {}

    /**
     * @param canGoInSack whether a drop could legitimately arrive via a sack summary; pets, for
     *                    instance, never do, so a sack line must not confirm them
     */
    public DropTracker(DropLog log, Predicate<DropRecord> canGoInSack)
    {
        this.log = log;
        this.canGoInSack = canGoInSack;
    }

    public DropLog log()
    {
        return log;
    }

    public void onConfirmed(Consumer<Confirmation> listener)
    {
        listeners.add(listener);
    }

    /**
     * Records a drop and starts waiting for it to arrive.
     *
     * @param repoId SkyBlock id from the NEU repo, used for exact matching when the arrived item carries one
     */
    public DropRecord record(RngDrop drop, @Nullable String repoId)
    {
        long now = System.currentTimeMillis();
        DropRecord record = new DropRecord(drop, repoId, now);
        log.add(record);
        Pending waiting = new Pending(record, ItemNames.normalize(drop.itemName()), now);

        for (Iterator<ItemGain> it = unclaimed.iterator(); it.hasNext(); )
        {
            ItemGain gain = it.next();
            if (now - gain.atMillis() <= ARRIVED_BEFORE_MS && matches(waiting, gain))
            {
                it.remove(); // one arrival confirms one drop
                confirm(record, gain);
                return record;
            }
        }
        pending.add(waiting);
        decide("§7Waiting for " + drop.itemName() + " to arrive");
        return record;
    }

    public void tick(Minecraft minecraft)
    {
        watcher.tick(minecraft).forEach(this::onGain);

        long now = System.currentTimeMillis();
        pending.removeIf(waiting -> {
            if (now - waiting.sinceMillis() <= ARRIVES_WITHIN_MS) return false;
            decide("§c" + waiting.record().item + "§7 was announced but never seen arriving");
            return true;
        });
    }

    /** Feed every game chat message; sack summaries are picked out here. */
    public void onChat(Component message, boolean overlay)
    {
        if (overlay) return;
        SackMessages.parse(message, System.currentTimeMillis()).forEach(this::onGain);
    }

    /** Clears transient state, e.g. on disconnect. Persisted records are kept. */
    public void reset()
    {
        watcher.reset();
        pending.clear();
        unclaimed.clear();
    }

    /** Recent arrivals and matching decisions, newest first, for {@code /archfiend log}. */
    public List<String> describe()
    {
        List<String> lines = new ArrayList<>();
        lines.add("§6Recent arrivals:");
        if (arrivals.isEmpty()) lines.add("§8  none yet");
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        for (ItemGain gain : arrivals)
        {
            lines.add("§8  " + clock.format(new Date(gain.atMillis())) + " §a+" + gain.count() + " §f" + gain.name()
                + (gain.skyblockId() != null ? " §8" + gain.skyblockId() : "") + " §7→ " + gain.destination());
        }
        lines.add("§6Decisions:");
        if (decisions.isEmpty()) lines.add("§8  none yet");
        decisions.forEach(line -> lines.add("  " + line));
        return lines;
    }

    /** Package-private for tests; production gains arrive through {@link #tick} and {@link #onChat}. */
    void onGain(ItemGain gain)
    {
        arrivals.addFirst(gain);
        while (arrivals.size() > RECENT_GAINS) arrivals.removeLast();

        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); )
        {
            Pending waiting = it.next();
            if (matches(waiting, gain))
            {
                it.remove();
                confirm(waiting.record(), gain);
                return;
            }
        }
        unclaimed.addFirst(gain);
        while (unclaimed.size() > RECENT_GAINS) unclaimed.removeLast();
    }

    private boolean matches(Pending waiting, ItemGain gain)
    {
        if (gain.inSack() && !canGoInSack.test(waiting.record())) return false;

        String expectedId = waiting.record().skyblockId;
        String arrivedId = gain.skyblockId();
        if (expectedId != null && arrivedId != null && !PET_ID.equals(arrivedId)) return expectedId.equalsIgnoreCase(arrivedId);
        return ItemNames.normalize(gain.name()).equals(waiting.normalizedName());
    }

    private void confirm(DropRecord record, ItemGain gain)
    {
        record.destination = gain.destination();
        if (gain.skyblockId() != null) record.skyblockId = gain.skyblockId();
        log.updated();
        decide("§a" + record.item + "§7 confirmed in " + gain.destination());
        Confirmation confirmation = new Confirmation(record, gain);
        listeners.forEach(listener -> listener.accept(confirmation));
    }

    private void decide(String line)
    {
        decisions.addFirst(line);
        while (decisions.size() > LOG_LINES) decisions.removeLast();
    }
}
