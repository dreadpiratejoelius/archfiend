package dev.archfiend.sequence;

import dev.archfiend.ArchfiendClient;
import dev.archfiend.config.ArchfiendConfig;
import dev.archfiend.config.ConfigManager;
import dev.archfiend.drop.DropParser;
import dev.archfiend.drop.IconResolver;
import dev.archfiend.drop.RngDrop;
import dev.archfiend.repo.LootPools;
import dev.archfiend.repo.NeuItem;
import dev.archfiend.repo.NeuItemStacks;
import dev.archfiend.repo.NeuRepo;
import dev.archfiend.repo.NeuRepoManager;
import dev.archfiend.track.DropRecord;
import dev.archfiend.track.DropTracker;
import dev.archfiend.track.EnemyDeathTracker;
import dev.archfiend.track.ItemGain;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Owns the queue of detected drops and drives one {@link DropSequence} at a time.
 * <p>
 * All entry points run on the client thread: chat packets are handled there by vanilla before
 * Fabric's message events fire, and ticks/HUD rendering are client-thread by definition.
 */
public final class SequencePlayer
{
    private static final int MAX_QUEUED = 8;
    /** Distinct past drops reused as tease symbols in the generic reel. */
    private static final int HISTORY_TEASES = 16;
    private static final int HISTORY_SCAN = 64;
    private static final int INVENTORY_COLOR = 0x55FF55;
    private static final int SACK_COLOR = 0x55FFFF;

    private final ConfigManager configManager;
    private final NeuRepoManager repoManager;
    private final DropTracker tracker;
    private final @Nullable EnemyDeathTracker deathTracker;
    private final Deque<QueuedDrop> queue = new ArrayDeque<>();
    /** Real item stacks seen arriving for drops still waiting in the queue. */
    private final Map<DropRecord, ItemStack> arrivedIcons = new IdentityHashMap<>();
    private final SplittableRandom random = new SplittableRandom();
    private DropParser parser;

    private @Nullable DropSequence current;
    private @Nullable QueuedDrop currentDrop;
    private boolean currentReleased;

    /**
     * A drop waiting to play.
     *
     * @param withheld its chat message was suppressed and must be re-sent on reveal
     * @param record   its persisted record, or {@code null} for previews that must not count as drops
     * @param deathPos world position where the enemy died, or {@code null} if unknown
     */
    private record QueuedDrop(RngDrop drop, boolean withheld, @Nullable DropRecord record, @Nullable Vec3 deathPos) {}

    public SequencePlayer(ConfigManager configManager, NeuRepoManager repoManager, DropTracker tracker)
    {
        this(configManager, repoManager, tracker, null);
    }

    public SequencePlayer(ConfigManager configManager, NeuRepoManager repoManager, DropTracker tracker, @Nullable EnemyDeathTracker deathTracker)
    {
        this.configManager = configManager;
        this.repoManager = repoManager;
        this.tracker = tracker;
        this.deathTracker = deathTracker;
        tracker.onConfirmed(this::onConfirmed);
        reloadParser();
    }

    public void reloadParser()
    {
        parser = new DropParser(configManager.get().triggers);
    }

    /**
     * Fabric {@code ALLOW_GAME} hook. Every recognised drop is recorded; those at or above the
     * minimum tier also get a sequence.
     *
     * @return {@code false} to suppress the message until the sequence reveals it
     */
    public boolean onGameMessage(Component message, boolean overlay)
    {
        ArchfiendConfig config = configManager.get();
        if (overlay || !config.enabled) return true;

        Optional<RngDrop> parsed = parser.parse(message);
        if (parsed.isEmpty()) return true;

        RngDrop drop = parsed.get();
        String repoId = repoManager.repo()
            .flatMap(repo -> repo.findByName(drop.itemName(), drop.itemColor()))
            .map(NeuItem::internalName)
            .orElse(null);
        DropRecord record = tracker.record(drop, repoId);

        if (!drop.tier().isAtLeast(config.minTier)) return true;
        boolean withhold = config.delayChatMessage;
        Vec3 deathPos = deathTracker != null ? deathTracker.findRecentDeath(System.currentTimeMillis()) : null;
        if (!enqueue(drop, withhold, record, deathPos)) return true;
        return !withhold;
    }

    /** Queues a preview that is not recorded as a real drop. */
    public boolean enqueuePreview(RngDrop drop)
    {
        return enqueue(drop, true, null, null);
    }

    private boolean enqueue(RngDrop drop, boolean withheld, @Nullable DropRecord record, @Nullable Vec3 deathPos)
    {
        if (queue.size() >= MAX_QUEUED)
        {
            ArchfiendClient.LOGGER.debug("Sequence queue full, letting '{}' through", drop.itemName());
            return false;
        }
        queue.addLast(new QueuedDrop(drop, withheld, record, deathPos));
        return true;
    }

    public void tick(Minecraft minecraft)
    {
        if (current == null)
        {
            QueuedDrop next = queue.pollFirst();
            if (next == null) return;
            start(minecraft, next);
        }

        current.tick();
        if (current.isFinished()) finishCurrent();
    }

    public void skip()
    {
        if (current != null) current.skip();
    }

    public boolean isActive()
    {
        return current != null;
    }

    /** Whether the vanilla HUD and hand should be hidden right now. */
    public boolean hidesHud()
    {
        return current != null && configManager.get().hideHud && current.hidesHud();
    }

    /** Whether only the chat overlay should be hidden (world styles hide chat but keep the rest of the HUD). */
    public boolean hidesChat()
    {
        return current != null && current.hidesChat() && (configManager.get().hideHud || current.worldChest());
    }

    public int queuedCount()
    {
        return queue.size();
    }

    /** Ends everything immediately, flushing any withheld chat messages so no drop is ever lost. */
    public void reset()
    {
        if (current != null) releaseCurrent();
        current = null;
        currentDrop = null;
        arrivedIcons.clear();
        QueuedDrop pending;
        while ((pending = queue.pollFirst()) != null)
        {
            if (pending.withheld()) sendToChat(pending.drop().message());
        }
        if (deathTracker != null) deathTracker.reset();
    }

    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker)
    {
        if (current == null) return;
        current.render(graphics, deltaTracker.getGameTimeDeltaPartialTick(true));
    }

    /** Renders the active sequence's world-space content into the level submit pass. */
    public void renderWorld(LevelRenderContext context)
    {
        if (current == null) return;
        current.renderWorld(
            context.poseStack(),
            context.submitNodeCollector(),
            context.levelState().cameraRenderState.pos,
            Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true)
        );
    }

    private void start(Minecraft minecraft, QueuedDrop queued)
    {
        ArchfiendConfig config = configManager.get();
        RngDrop drop = queued.drop();
        DropRecord record = queued.record();

        Optional<NeuRepo> repo = repoManager.repo();
        Optional<NeuItem> repoItem = repo.flatMap(r -> findRepoItem(r, record, drop.itemName(), drop.itemColor()));
        NeuItemStacks stacks = NeuItemStacks.forClient();

        // The arrived stack is exact (it carries Hypixel's own model); the repo is the next best thing.
        ItemStack arrived = record != null ? arrivedIcons.remove(record) : null;
        ItemStack icon = Optional.ofNullable(arrived)
            .or(() -> IconResolver.findInInventory(minecraft, drop.itemName()))
            .or(() -> repoItem.flatMap(stacks::create))
            .or(() -> IconResolver.guessVanilla(drop.itemName()))
            .orElseGet(() -> fallbackIcon(drop));

        Optional<SymbolPool> lootPool = config.repo.useLootTables && repo.isPresent() && repoItem.isPresent()
            ? LootPools.forDrop(repo.get(), stacks, repoItem.get().internalName())
            : Optional.empty();
        SymbolPool pool = lootPool.orElseGet(() -> SymbolPool.fromConfig(config, historyTeases(repo, stacks)));

        SequenceContext context = new SequenceContext(
            drop,
            new Symbol(icon, drop.itemComponent(), drop.itemColor()),
            pool,
            random.split(),
            new SequenceSounds(config.volume),
            config.spinTicks,
            config.revealTicks,
            config.scale,
            config.nearMiss,
            queued.deathPos(),
            this::releaseCurrent
        );

        currentDrop = queued;
        currentReleased = false;
        current = config.style.create(context);
        if (record != null && record.destination != null) current.setDestination(badge(record.destination));
    }

    private void onConfirmed(DropTracker.Confirmation confirmation)
    {
        DropRecord record = confirmation.record();
        ItemGain gain = confirmation.gain();

        if (current != null && currentDrop != null && currentDrop.record() == record)
        {
            if (gain.stack() != null) current.setTargetIcon(gain.stack());
            current.setDestination(badge(gain.destination()));
            return;
        }
        if (gain.stack() != null && queue.stream().anyMatch(queued -> queued.record() == record))
        {
            arrivedIcons.put(record, gain.stack());
        }
    }

    /** Past drops (persisted across sessions) as believable teases for the generic reel. */
    private List<Symbol> historyTeases(Optional<NeuRepo> repo, NeuItemStacks stacks)
    {
        List<Symbol> teases = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (DropRecord past : tracker.log().recent(HISTORY_SCAN))
        {
            if (teases.size() >= HISTORY_TEASES) break;
            if (!seen.add(past.item)) continue;

            ItemStack icon = repo.flatMap(r -> findRepoItem(r, past, past.item, past.itemColor))
                .flatMap(stacks::create)
                .orElseGet(() -> {
                    ItemStack fallback = new ItemStack(past.tier.fallbackIcon());
                    fallback.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                    return fallback;
                });
            teases.add(new Symbol(icon, Component.literal(past.item).withColor(past.itemColor), past.itemColor));
        }
        return teases;
    }

    /** Prefers the exact SkyBlock id seen on the arrived item over a name lookup. */
    private static Optional<NeuItem> findRepoItem(NeuRepo repo, @Nullable DropRecord record, String name, int color)
    {
        if (record != null && record.skyblockId != null)
        {
            Optional<NeuItem> exact = repo.item(record.skyblockId);
            if (exact.isPresent()) return exact;
        }
        return repo.findByName(name, color);
    }

    private static Component badge(String destination)
    {
        return ItemGain.INVENTORY.equals(destination)
            ? Component.literal("✔ " + destination).withColor(INVENTORY_COLOR)
            : Component.literal("→ " + destination).withColor(SACK_COLOR);
    }

    private static ItemStack fallbackIcon(RngDrop drop)
    {
        ItemStack stack = new ItemStack(drop.tier().fallbackIcon());
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return stack;
    }

    private void releaseCurrent()
    {
        if (currentReleased || currentDrop == null) return;
        currentReleased = true;
        if (currentDrop.withheld()) sendToChat(currentDrop.drop().message());
    }

    private void finishCurrent()
    {
        releaseCurrent();
        current = null;
        currentDrop = null;
    }

    private static void sendToChat(Component message)
    {
        Minecraft.getInstance().gui.hud.getChat().addServerSystemMessage(message);
    }
}
