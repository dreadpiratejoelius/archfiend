package dev.archfiend.util;

import dev.archfiend.ArchfiendClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Crash-safe file replacement: write to a sibling temp file, then swap it in.
 * <p>
 * On Windows the swap can transiently fail while antivirus or the search indexer holds the target
 * open, so it is retried briefly before giving up.
 */
public final class AtomicFiles
{
    private static final int REPLACE_ATTEMPTS = 3;
    private static final long REPLACE_RETRY_DELAY_MS = 20;

    private AtomicFiles() {}

    public static Path tempSibling(Path target)
    {
        return target.resolveSibling(target.getFileName() + ".tmp");
    }

    /** Atomically writes {@code content}, falling back to an in-place write if the target stays locked. */
    public static void writeString(Path target, String content) throws IOException
    {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = tempSibling(target);
        try
        {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try
            {
                replace(temp, target);
            }
            catch (AccessDeniedException e)
            {
                ArchfiendClient.LOGGER.warn("{} is locked, writing it in place", target);
                Files.writeString(target, content, StandardCharsets.UTF_8);
            }
        }
        finally
        {
            Files.deleteIfExists(temp);
        }
    }

    /** Moves {@code source} over {@code target}, atomically where the file system allows it. */
    public static void replace(Path source, Path target) throws IOException
    {
        for (int attempt = 1; ; attempt++)
        {
            try
            {
                move(source, target);
                return;
            }
            catch (AccessDeniedException e)
            {
                if (attempt >= REPLACE_ATTEMPTS) throw e;
                sleepQuietly(REPLACE_RETRY_DELAY_MS * attempt);
            }
        }
    }

    private static void move(Path source, Path target) throws IOException
    {
        try
        {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void sleepQuietly(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
