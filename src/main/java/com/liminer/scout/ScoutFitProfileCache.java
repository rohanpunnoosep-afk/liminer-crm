package com.liminer.scout;

import com.liminer.core.InvestorProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.json.JSONObject;

/*
 * ScoutFitProfileCache — persistent, client-independent cache for ScoutFitTierB's
 * InvestorProfile extractions, keyed by crd + snapshotMonth. Profile extraction
 * (brochure/website/LinkedIn -> InvestorProfile) depends only on the candidate
 * and the ADV snapshot month, never on which client is being scored, so a
 * candidate extracted once for client A costs $0 for client B.
 *
 * ScrapeCache (in-memory, per-batch-run, not persisted to disk) doesn't fit this
 * keying, so this is a small parallel file cache under data/scout/profiles/,
 * one JSON file per key, following FileScoutUniverseStore/ScoutSignalScorer's
 * file-cache style.
 */
public class ScoutFitProfileCache
{
    private static final Path DEFAULT_CACHE_DIR = Paths.get("data", "scout", "profiles");

    private final Path cacheDir0;

    public static class CachedProfile
    {
        public InvestorProfile profile;
        public String profileSource;

        public CachedProfile(InvestorProfile profile0, String profileSource0)
        {
            profile = profile0;
            profileSource = profileSource0;
        }
    }

    public ScoutFitProfileCache() { this(DEFAULT_CACHE_DIR); }

    public ScoutFitProfileCache(Path cacheDir0) { this.cacheDir0 = cacheDir0; }

    public CachedProfile load(int crd0, String snapshotMonth0) throws IOException
    {
        Path file0 = keyFile(crd0, snapshotMonth0);
        if (!Files.exists(file0))
        {
            return null;
        }

        String json0 = Files.readString(file0, StandardCharsets.UTF_8);
        JSONObject o0 = new JSONObject(json0);

        InvestorProfile profile0 = InvestorProfile.fromIntelligenceJsonString(o0.optString("intelligenceJson", ""));
        String source0 = o0.optString("profileSource", ScoutFitResult.SOURCE_NONE);
        return new CachedProfile(profile0, source0);
    }

    public void save(int crd0, String snapshotMonth0, CachedProfile cached0) throws IOException
    {
        Files.createDirectories(cacheDir0);

        JSONObject o0 = new JSONObject();
        String intelligenceJson0 = (cached0.profile == null || cached0.profile.intelligenceJson == null)
            ? "" : cached0.profile.intelligenceJson;
        o0.put("intelligenceJson", intelligenceJson0);
        o0.put("profileSource", cached0.profileSource == null ? ScoutFitResult.SOURCE_NONE : cached0.profileSource);

        Files.writeString(keyFile(crd0, snapshotMonth0), o0.toString(), StandardCharsets.UTF_8);
    }

    private Path keyFile(int crd0, String snapshotMonth0)
    {
        String month0 = snapshotMonth0 == null || snapshotMonth0.trim().isEmpty() ? "unknown" : snapshotMonth0.trim();
        return cacheDir0.resolve(crd0 + "_" + month0 + ".json");
    }
}
