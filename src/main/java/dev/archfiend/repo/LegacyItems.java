package dev.archfiend.repo;

import java.util.Map;

/**
 * Maps 1.8 item ids (plus damage/metadata) to their modern names. Only covers ids that were renamed
 * or split by "the flattening"; everything else kept its name and passes through unchanged.
 */
public final class LegacyItems
{
    private static final String[] COLORS = {
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };

    /** Legacy dye damage order differs from the wool colour order. */
    private static final String[] DYES = {
        "ink_sac", "red_dye", "green_dye", "cocoa_beans", "lapis_lazuli", "purple_dye", "cyan_dye", "light_gray_dye",
        "gray_dye", "pink_dye", "lime_dye", "yellow_dye", "light_blue_dye", "magenta_dye", "orange_dye", "bone_meal"
    };

    private static final Map<String, String[]> VARIANTS = Map.ofEntries(
        Map.entry("skull", new String[] {"skeleton_skull", "wither_skeleton_skull", "zombie_head", "player_head", "creeper_head", "dragon_head"}),
        Map.entry("fish", new String[] {"cod", "salmon", "tropical_fish", "pufferfish"}),
        Map.entry("cooked_fish", new String[] {"cooked_cod", "cooked_salmon"}),
        Map.entry("log", new String[] {"oak_log", "spruce_log", "birch_log", "jungle_log"}),
        Map.entry("log2", new String[] {"acacia_log", "dark_oak_log"}),
        Map.entry("planks", new String[] {"oak_planks", "spruce_planks", "birch_planks", "jungle_planks", "acacia_planks", "dark_oak_planks"}),
        Map.entry("sapling", new String[] {"oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling", "acacia_sapling", "dark_oak_sapling"}),
        Map.entry("leaves", new String[] {"oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves"}),
        Map.entry("leaves2", new String[] {"acacia_leaves", "dark_oak_leaves"}),
        Map.entry("golden_apple", new String[] {"golden_apple", "enchanted_golden_apple"}),
        Map.entry("coal", new String[] {"coal", "charcoal"}),
        Map.entry("sand", new String[] {"sand", "red_sand"}),
        Map.entry("dirt", new String[] {"dirt", "coarse_dirt", "podzol"}),
        Map.entry("sponge", new String[] {"sponge", "wet_sponge"}),
        Map.entry("stone", new String[] {"stone", "granite", "polished_granite", "diorite", "polished_diorite", "andesite", "polished_andesite"}),
        Map.entry("stonebrick", new String[] {"stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks"}),
        Map.entry("prismarine", new String[] {"prismarine", "prismarine_bricks", "dark_prismarine"}),
        Map.entry("sandstone", new String[] {"sandstone", "chiseled_sandstone", "cut_sandstone"}),
        Map.entry("red_sandstone", new String[] {"red_sandstone", "chiseled_red_sandstone", "cut_red_sandstone"}),
        Map.entry("quartz_block", new String[] {"quartz_block", "chiseled_quartz_block", "quartz_pillar"}),
        Map.entry("tallgrass", new String[] {"dead_bush", "short_grass", "fern"}),
        Map.entry("red_flower", new String[] {"poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy"}),
        Map.entry("double_plant", new String[] {"sunflower", "lilac", "tall_grass", "large_fern", "rose_bush", "peony"}),
        Map.entry("anvil", new String[] {"anvil", "chipped_anvil", "damaged_anvil"}),
        Map.entry("dye", DYES)
    );

    private static final Map<String, String> COLORED = Map.of(
        "wool", "wool",
        "carpet", "carpet",
        "stained_glass", "stained_glass",
        "stained_glass_pane", "stained_glass_pane",
        "stained_hardened_clay", "terracotta",
        "concrete", "concrete",
        "concrete_powder", "concrete_powder"
    );

    private static final Map<String, String> RENAMES = Map.ofEntries(
        Map.entry("reeds", "sugar_cane"),
        Map.entry("speckled_melon", "glistering_melon_slice"),
        Map.entry("melon", "melon_slice"),
        Map.entry("melon_block", "melon"),
        Map.entry("netherbrick", "nether_brick"),
        Map.entry("nether_brick", "nether_bricks"),
        Map.entry("red_nether_brick", "red_nether_bricks"),
        Map.entry("fireworks", "firework_rocket"),
        Map.entry("firework_charge", "firework_star"),
        Map.entry("boat", "oak_boat"),
        Map.entry("wooden_door", "oak_door"),
        Map.entry("sign", "oak_sign"),
        Map.entry("fence", "oak_fence"),
        Map.entry("fence_gate", "oak_fence_gate"),
        Map.entry("trapdoor", "oak_trapdoor"),
        Map.entry("wooden_button", "oak_button"),
        Map.entry("wooden_pressure_plate", "oak_pressure_plate"),
        Map.entry("wooden_slab", "oak_slab"),
        Map.entry("stone_slab", "smooth_stone_slab"),
        Map.entry("lit_pumpkin", "jack_o_lantern"),
        Map.entry("mob_spawner", "spawner"),
        Map.entry("web", "cobweb"),
        Map.entry("deadbush", "dead_bush"),
        Map.entry("yellow_flower", "dandelion"),
        Map.entry("waterlily", "lily_pad"),
        Map.entry("noteblock", "note_block"),
        Map.entry("grass", "grass_block"),
        Map.entry("hardened_clay", "terracotta"),
        Map.entry("brick_block", "bricks"),
        Map.entry("quartz_ore", "nether_quartz_ore"),
        Map.entry("golden_rail", "powered_rail"),
        Map.entry("snow_layer", "snow"),
        Map.entry("snow", "snow_block"),
        Map.entry("chorus_fruit_popped", "popped_chorus_fruit"),
        Map.entry("magma", "magma_block"),
        Map.entry("slime", "slime_block"),
        Map.entry("end_bricks", "end_stone_bricks"),
        Map.entry("monster_egg", "infested_stone"),
        Map.entry("silver_shulker_box", "light_gray_shulker_box"),
        Map.entry("bed", "red_bed"),
        Map.entry("spawn_egg", "pig_spawn_egg"),
        Map.entry("totem", "totem_of_undying")
    );

    private LegacyItems() {}

    /**
     * @param legacyId 1.8 id, with or without the {@code minecraft:} namespace
     * @param damage   1.8 metadata
     * @return the modern item path in the {@code minecraft} namespace
     */
    public static String toModernPath(String legacyId, int damage)
    {
        String path = legacyId.startsWith("minecraft:") ? legacyId.substring("minecraft:".length()) : legacyId;

        String[] variants = VARIANTS.get(path);
        if (variants != null) return variants[damage >= 0 && damage < variants.length ? damage : 0];

        String coloredSuffix = COLORED.get(path);
        if (coloredSuffix != null) return COLORS[damage & 15] + "_" + coloredSuffix;

        if (path.equals("banner")) return COLORS[15 - (damage & 15)] + "_banner"; // banners count colours backwards
        if (path.startsWith("record_")) return "music_disc_" + path.substring("record_".length());

        return RENAMES.getOrDefault(path, path);
    }
}
