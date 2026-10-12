package dev.archfiend.track;

import dev.archfiend.drop.DropTier;
import dev.archfiend.drop.RngDrop;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropTrackerTest
{
    @TempDir
    Path dir;

    private DropLog log;
    private DropTracker tracker;
    private final List<DropTracker.Confirmation> confirmations = new ArrayList<>();

    @BeforeEach
    void setUp()
    {
        log = new DropLog(dir.resolve("drops.json"));
        tracker = new DropTracker(log, record -> record.tier != DropTier.PET);
        tracker.onConfirmed(confirmations::add);
    }

    @AfterEach
    void tearDown()
    {
        log.close();
    }

    @Test
    void parsesSackSummaryHover()
    {
        List<ItemGain> gains = SackMessages.parse(sackMessage("+1 Warden Heart (Combat Sack)\n  +64 Revenant Flesh (Combat Sack)"), 0);

        assertEquals(2, gains.size());
        assertEquals("Warden Heart", gains.get(0).name());
        assertEquals("Combat Sack", gains.get(0).destination());
        assertEquals(64, gains.get(1).count());
        assertTrue(gains.get(0).inSack());
    }

    @Test
    void ignoresOrdinaryMessages()
    {
        assertTrue(SackMessages.parse(Component.literal("+1 Warden Heart (Combat Sack)"), 0).isEmpty());
    }

    @Test
    void confirmsDropWhenItArrivesInASack()
    {
        DropRecord record = tracker.record(drop("Warden Heart"), "WARDEN_HEART");
        assertNull(record.destination);

        tracker.onChat(sackMessage("+1 Warden Heart (Combat Sack)"), false);

        assertEquals("Combat Sack", record.destination);
        assertEquals(1, confirmations.size());
        assertEquals(record, confirmations.getFirst().record());
    }

    @Test
    void confirmsItemThatArrivedBeforeTheChatLine()
    {
        tracker.onChat(sackMessage("+1 Foul Flesh (Combat Sack)"), false);
        DropRecord record = tracker.record(drop("Foul Flesh"), null);

        assertEquals("Combat Sack", record.destination);
    }

    @Test
    void oneArrivalConfirmsOnlyOneDrop()
    {
        DropRecord first = tracker.record(drop("Foul Flesh"), null);
        DropRecord second = tracker.record(drop("Foul Flesh"), null);
        tracker.onChat(sackMessage("+1 Foul Flesh (Combat Sack)"), false);

        assertEquals("Combat Sack", first.destination);
        assertNull(second.destination);
    }

    @Test
    void unrelatedArrivalDoesNotConfirm()
    {
        DropRecord record = tracker.record(drop("Warden Heart"), null);
        tracker.onChat(sackMessage("+3 Revenant Flesh (Combat Sack)"), false);

        assertNull(record.destination);
        assertTrue(confirmations.isEmpty());
    }

    @Test
    void petsAreNeverConfirmedBySackLines()
    {
        DropRecord pet = tracker.record(drop(DropTier.PET, "Scatha"), "SCATHA;4");
        tracker.onChat(sackMessage("+1 Scatha (Mining Sack)"), false);

        assertNull(pet.destination);
    }

    @Test
    void petItemsMatchByNameBecauseTheirIdIsGeneric()
    {
        DropRecord pet = tracker.record(drop(DropTier.PET, "Scatha"), "SCATHA;4");
        assertTrue(pet.skyblockId.contains(";"));

        // What the inventory watcher reports for a real pet: levelled name, generic PET id.
        tracker.onGain(new ItemGain("[Lvl 1] Scatha", "PET", 1, null, ItemGain.INVENTORY, System.currentTimeMillis()));
        assertEquals(ItemGain.INVENTORY, pet.destination);
    }

    @Test
    void persistsDropsAndCountsAcrossRestarts()
    {
        tracker.record(drop("Warden Heart"), "WARDEN_HEART");
        tracker.record(drop("Foul Flesh"), null);
        tracker.onChat(sackMessage("+1 Warden Heart (Combat Sack)"), false);
        log.close(); // flushes the background writer

        DropLog reloaded = new DropLog(dir.resolve("drops.json"));
        reloaded.load();
        assertEquals(2, reloaded.total());
        assertEquals(2, reloaded.count(DropTier.CRAZY_RARE));
        List<DropRecord> recent = reloaded.recent(10);
        assertEquals("Foul Flesh", recent.get(0).item); // newest first
        assertEquals("Combat Sack", recent.get(1).destination);
        assertEquals(312, recent.get(1).magicFind);
        reloaded.close();
    }

    private static RngDrop drop(String item)
    {
        return drop(DropTier.CRAZY_RARE, item);
    }

    private static RngDrop drop(DropTier tier, String item)
    {
        Component message = Component.literal(tier.displayName.toUpperCase() + "! (" + item + ")");
        return new RngDrop(tier, tier.displayName.toUpperCase() + "!", tier.defaultColor, item, 0xFFAA00, 312, message);
    }

    private static Component sackMessage(String hoverLines)
    {
        Component hover = Component.literal("Added items:\n  " + hoverLines + "\n\nThis message can be disabled in the settings.");
        return Component.literal("[Sacks] ")
            .append(Component.literal("+N items").withStyle(Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(hover))))
            .append(Component.literal(". (Last 5s.)"));
    }
}
