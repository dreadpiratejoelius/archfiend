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
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
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
    private static final int HISTORY_SIZE = 16;
    private static final int ICON_SCAN_INTERVAL = 5;

    private final ConfigManager configManager;
    private final NeuRepoManager repoManager;
    private final Deque<QueuedDrop> queue = new ArrayDeque<>();
    private final Deque<Symbol> history = new ArrayDeque<>();
    private final SplittableRandom random = new SplittableRandom();
    private DropParser parser;

    private @Nullable DropSequence current;
    private @Nullable QueuedDrop currentDrop;
    private boolean currentReleased;
    private boolean iconResolved;
    private int ticksSinceIconScan;

    /** A drop waiting to play; {@code withheld} means its chat message was suppressed and must be re-sent. */
    private record QueuedDrop(RngDrop drop, boolean withheld) {}

    public SequencePlayer(ConfigManager configManager, NeuRepoManager repoManager)
    {
        this.configManager = configManager;
        this.repoManager = repoManager;
        reloadParser();
    }

    public void reloadParser()
    {
        parser = new DropParser(configManager.get().triggers);
    }

    /**
     * Fabric {@code ALLOW_GAME} hook.
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
        if (!drop.tier().isAtLeast(config.minTier)) return true;

        boolean withhold = config.delayChatMessage;
        if (!enqueue(drop, withhold)) return true;
        return !withhold;
    }

    /**
     * @param withheld whether the drop's chat message has been suppressed and must be re-sent on reveal
     * @return {@code false} if the queue is full and the drop was ignored
     */
    public boolean enqueue(RngDrop drop, boolean withheld)
    {
        if (queue.size() >= MAX_QUEUED)
        {
            ArchfiendClient.LOGGER.debug("Sequence queue full, letting '{}' through", drop.itemName());
            return false;
        }
        queue.addLast(new QueuedDrop(drop, withheld));
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

        resolveIcon(minecraft);
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
        QueuedDrop pending;
        while ((pending = queue.pollFirst()) != null)
        {
            if (pending.withheld()) sendToChat(pending.drop().message());
        }
    }

    public void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker)
    {
        if (current == null) return;
        current.render(graphics, deltaTracker.getGameTimeDeltaPartialTick(true));
    }

    private void start(Minecraft minecraft, QueuedDrop queued)
    {
        ArchfiendConfig config = configManager.get();
        RngDrop drop = queued.drop();

        Optional<NeuRepo> repo = repoManager.repo();
        Optional<NeuItem> repoItem = repo.flatMap(r -> r.findByName(drop.itemName(), drop.itemColor()));
        NeuItemStacks stacks = NeuItemStacks.forClient();

        // The inventory copy is exact (it carries Hypixel's own model); the repo is the next best thing.
        Optional<ItemStack> inventoryIcon = IconResolver.findInInventory(minecraft, drop.itemName());
        iconResolved = inventoryIcon.isPresent();
        ticksSinceIconScan = 0;
        ItemStack icon = inventoryIcon
            .or(() -> repoItem.flatMap(stacks::create))
            .or(() -> IconResolver.guessVanilla(drop.itemName()))
            .orElseGet(() -> fallbackIcon(drop));

        Optional<SymbolPool> lootPool = config.repo.useLootTables && repo.isPresent() && repoItem.isPresent()
            ? LootPools.forDrop(repo.get(), stacks, repoItem.get().internalName())
            : Optional.empty();
        SymbolPool pool = lootPool.orElseGet(() -> SymbolPool.fromConfig(config, history));

        Symbol target = new Symbol(icon, drop.itemComponent(), drop.itemColor());
        SequenceContext context = new SequenceContext(
            drop,
            target,
            pool,
            random.split(),
            new SequenceSounds(config.volume),
            config.spinTicks,
            config.revealTicks,
            config.scale,
            config.nearMiss,
            this::onReveal
        );

        currentDrop = queued;
        currentReleased = false;
        current = config.style.create(context);
    }

    /** The real item usually lands in the inventory a moment after the chat message; swap its icon in. */
    private void resolveIcon(Minecraft minecraft)
    {
        if (iconResolved || current == null || currentDrop == null || current.isRevealed()) return;
        if (++ticksSinceIconScan < ICON_SCAN_INTERVAL) return;
        ticksSinceIconScan = 0;

        IconResolver.findInInventory(minecraft, currentDrop.drop().itemName()).ifPresent(icon -> {
            current.setTargetIcon(icon);
            iconResolved = true;
        });
    }

    private void onReveal()
    {
        releaseCurrent();
        // Real drops make for believable teases in later rolls.
        if (current != null)
        {
            history.addFirst(current.targetSymbol());
            while (history.size() > HISTORY_SIZE) history.removeLast();
        }
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
