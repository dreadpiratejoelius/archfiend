package dev.archfiend.sequence;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/** UI sounds for a sequence, scaled by the configured master volume. */
public record SequenceSounds(float volume)
{
    public void chestThud(float impactSpeed)
    {
        float strength = Math.min(1f, impactSpeed / 14f);
        play(SoundEvents.WOOD_PLACE, 0.55f + 0.25f * (1f - strength), 0.4f + 0.6f * strength);
        if (strength > 0.6f) play(SoundEvents.NOTE_BLOCK_BASEDRUM, 0.6f, 0.6f * strength);
    }

    public void chestRattle()
    {
        play(SoundEvents.CHEST_LOCKED, 0.9f + (float) Math.random() * 0.3f, 0.5f);
    }

    public void chestOpen()
    {
        play(SoundEvents.CHEST_OPEN, 0.8f, 1f);
        play(SoundEvents.AMETHYST_BLOCK_RESONATE, 1.2f, 0.7f);
    }

    public void questionRise()
    {
        play(SoundEvents.ENCHANTMENT_TABLE_USE, 1.2f, 0.8f);
    }

    public void colorTick(float pitch)
    {
        play(SoundEvents.AMETHYST_BLOCK_CHIME, pitch, 0.45f);
    }

    public void colorSettle()
    {
        play(SoundEvents.BEACON_POWER_SELECT, 1.3f, 0.5f);
    }

    public void reveal(boolean jackpot)
    {
        if (jackpot)
        {
            play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.8f);
            play(SoundEvents.BEACON_ACTIVATE, 1.4f, 0.5f);
        }
        else
        {
            play(SoundEvents.PLAYER_LEVELUP, 1.2f, 0.6f);
            play(SoundEvents.NOTE_BLOCK_PLING, 2f, 0.5f);
        }
    }

    // ---- world-space variants, positioned at the falling chest ----

    public void chestThud(Level level, double x, double y, double z, float impactSpeed)
    {
        float strength = Math.min(1f, impactSpeed / 14f);
        playAt(level, x, y, z, SoundEvents.WOOD_PLACE, 0.55f + 0.25f * (1f - strength), 0.4f + 0.6f * strength);
        if (strength > 0.6f) playAt(level, x, y, z, SoundEvents.NOTE_BLOCK_BASEDRUM.value(), 0.6f, 0.6f * strength);
    }

    public void chestRattle(Level level, double x, double y, double z)
    {
        playAt(level, x, y, z, SoundEvents.CHEST_LOCKED, 0.9f + (float) Math.random() * 0.3f, 0.5f);
    }

    public void chestOpen(Level level, double x, double y, double z)
    {
        playAt(level, x, y, z, SoundEvents.CHEST_OPEN, 0.8f, 1f);
        playAt(level, x, y, z, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.2f, 0.7f);
    }

    public void questionRise(Level level, double x, double y, double z)
    {
        playAt(level, x, y, z, SoundEvents.ENCHANTMENT_TABLE_USE, 1.2f, 0.8f);
    }

    public void colorTick(Level level, double x, double y, double z, float pitch)
    {
        playAt(level, x, y, z, SoundEvents.AMETHYST_BLOCK_CHIME, pitch, 0.45f);
    }

    public void colorSettle(Level level, double x, double y, double z)
    {
        playAt(level, x, y, z, SoundEvents.BEACON_POWER_SELECT, 1.3f, 0.5f);
    }

    private void playAt(Level level, double x, double y, double z, SoundEvent sound, float pitch, float baseVolume)
    {
        float effective = baseVolume * volume;
        if (effective <= 0f) return;
        level.playLocalSound(x, y, z, sound, SoundSource.PLAYERS, effective, Mth.clamp(pitch, 0.5f, 2f), false);
    }

    private void play(Holder<SoundEvent> sound, float pitch, float baseVolume)
    {
        play(sound.value(), pitch, baseVolume);
    }

    private void play(SoundEvent sound, float pitch, float baseVolume)
    {
        float effective = baseVolume * volume;
        if (effective <= 0f) return;
        Minecraft.getInstance().getSoundManager().play(
            SimpleSoundInstance.forUI(sound, Mth.clamp(pitch, 0.5f, 2f), effective)
        );
    }
}
