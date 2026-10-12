package dev.archfiend.config;

import dev.archfiend.drop.DropTier;
import dev.archfiend.sequence.SequenceStyle;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Persisted settings. Public mutable fields keep Gson (de)serialisation trivial;
 * {@link #sanitize()} must be called after loading to repair out-of-range or missing values.
 */
public final class ArchfiendConfig
{
    public static final int MIN_SPIN_TICKS = 20;
    public static final int MAX_SPIN_TICKS = 400;
    public static final float MIN_SCALE = 0.25f;
    public static final float MAX_SCALE = 4f;
    private static final int CURRENT_VERSION = 1;

    /** Schema version for one-off migrations; missing (0) in files written before migrations existed. */
    public int configVersion = 0;

    public boolean enabled = true;
    public SequenceStyle style = SequenceStyle.REEL;
    public DropTier minTier = DropTier.CRAZY_RARE;

    /** Ticks the reel(s) spin before landing. Slot machine adds a per-reel stagger on top. */
    public int spinTicks = 100;
    /** Ticks the landed result stays on screen before fading out. */
    public int revealTicks = 50;
    public float scale = 1f;
    public float volume = 1f;

    /** Hold the drop chat message back until the sequence reveals it, so chat can't spoil the roll. */
    public boolean delayChatMessage = true;
    /** Hide the vanilla HUD and first-person hand while a sequence plays. */
    public boolean hideHud = true;
    /** Mix fake high-tier symbols into the reel for suspense. */
    public boolean teaseSymbols = true;
    /** Put a tease symbol right after the winning slot so the reel "almost" lands on it. */
    public boolean nearMiss = true;

    /** Chat prefixes that trigger a sequence. Matched against the start of the unformatted message. */
    public List<Trigger> triggers = defaultTriggers();
    /** Filler symbols used when no loot table is known for a drop. {@code item} is a namespaced item id. */
    public List<Filler> fillers = defaultFillers();

    /** NEU item repo: real SkyBlock icons and per-mob loot tables. */
    public Repo repo = new Repo();

    public record Trigger(String prefix, DropTier tier) {}

    public record Filler(String item, String name, String color, int weight, boolean glint) {}

    public static final class Repo
    {
        public static final String DEFAULT_REPOSITORY = "NotEnoughUpdates/NotEnoughUpdates-REPO";
        public static final String DEFAULT_BRANCH = "master";
        private static final Pattern REPOSITORY = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
        private static final Pattern BRANCH = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_./-]*$");

        /** Schema version for one-off migrations; missing (0) in files written before migrations existed. */
    public int configVersion = 0;

    public boolean enabled = true;
        /** Check GitHub for a newer repo commit on startup. */
        public boolean autoUpdate = true;
        /** GitHub {@code owner/name}; point this at a fork if the main repo is unavailable. */
        public String repository = DEFAULT_REPOSITORY;
        public String branch = DEFAULT_BRANCH;
        /** Fill the reels with the real loot table of the mob that dropped the item. */
        public boolean useLootTables = true;

        void sanitize()
        {
            if (repository == null || !REPOSITORY.matcher(repository).matches()) repository = DEFAULT_REPOSITORY;
            if (branch == null || !BRANCH.matcher(branch).matches() || branch.contains("..")) branch = DEFAULT_BRANCH;
        }
    }

    public void sanitize()
    {
        if (style == null) style = SequenceStyle.REEL;
        if (minTier == null) minTier = DropTier.CRAZY_RARE;
        spinTicks = Mth.clamp(spinTicks, MIN_SPIN_TICKS, MAX_SPIN_TICKS);
        revealTicks = Mth.clamp(revealTicks, 10, 200);
        scale = Float.isFinite(scale) ? Mth.clamp(scale, MIN_SCALE, MAX_SCALE) : 1f;
        volume = Float.isFinite(volume) ? Mth.clamp(volume, 0f, 2f) : 1f;

        triggers = triggers == null ? new ArrayList<>() : new ArrayList<>(triggers);
        triggers.removeIf(t -> t == null || t.prefix() == null || t.prefix().isBlank() || t.tier() == null);
        if (triggers.isEmpty()) triggers = defaultTriggers();
        migrate();

        if (repo == null) repo = new Repo();
        repo.sanitize();

        fillers = fillers == null ? new ArrayList<>() : new ArrayList<>(fillers);
        fillers.removeIf(f -> f == null || f.item() == null || f.name() == null || f.weight() <= 0);
        if (fillers.isEmpty()) fillers = defaultFillers();
    }

    /**
     * Applies changes to built-in defaults that a saved config would otherwise never pick up.
     * Each step runs once; user edits made after it are left alone.
     */
    private void migrate()
    {
        if (configVersion < 1)
        {
            addTriggerIfMissing(new Trigger("EXTREMELY RARE DROP!", DropTier.EXTREMELY_RARE));
        }
        configVersion = CURRENT_VERSION;
    }

    private void addTriggerIfMissing(Trigger trigger)
    {
        if (triggers.stream().noneMatch(t -> t.prefix().equals(trigger.prefix()))) triggers.add(trigger);
    }

    private static List<Trigger> defaultTriggers()
    {
        return new ArrayList<>(List.of(
            new Trigger("RARE DROP!", DropTier.RARE),
            new Trigger("VERY RARE DROP!", DropTier.VERY_RARE),
            new Trigger("CRAZY RARE DROP!", DropTier.CRAZY_RARE),
            new Trigger("PET DROP!", DropTier.PET),
            new Trigger("EXTREMELY RARE DROP!", DropTier.EXTREMELY_RARE),
            new Trigger("INSANE DROP!", DropTier.INSANE),
            new Trigger("PRAY TO RNGESUS DROP!", DropTier.PRAY_TO_RNGESUS),
            new Trigger("PRAY RNGESUS DROP!", DropTier.PRAY_TO_RNGESUS),
            new Trigger("RNGESUS INCARNATE", DropTier.RNGESUS_INCARNATE)
        ));
    }

    private static List<Filler> defaultFillers()
    {
        return new ArrayList<>(List.of(
            new Filler("minecraft:gold_nugget", "Coins", "#FFAA00", 14, false),
            new Filler("minecraft:rotten_flesh", "Rotten Flesh", "#FFFFFF", 12, false),
            new Filler("minecraft:bone", "Bone", "#FFFFFF", 10, false),
            new Filler("minecraft:string", "String", "#FFFFFF", 10, false),
            new Filler("minecraft:coal", "Coal", "#FFFFFF", 8, false),
            new Filler("minecraft:experience_bottle", "Combat XP", "#55FFFF", 8, false),
            new Filler("minecraft:ender_pearl", "Ender Pearl", "#FFFFFF", 6, false),
            new Filler("minecraft:rotten_flesh", "Enchanted Rotten Flesh", "#55FF55", 5, true),
            new Filler("minecraft:ender_pearl", "Enchanted Ender Pearl", "#55FF55", 4, true),
            new Filler("minecraft:bone", "Enchanted Bone", "#55FF55", 4, true)
        ));
    }
}
