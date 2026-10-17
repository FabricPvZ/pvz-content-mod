package cn.enaium.fabricpvz.contentmod.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import cn.enaium.fabricpvz.api.PvzFabricApi;
import cn.enaium.fabricpvz.api.lawn.LawnEvents;
import cn.enaium.fabricpvz.api.lifecycle.GameLifecycleEvents;
import cn.enaium.fabricpvz.api.plant.PlantRegistry;
import cn.enaium.fabricpvz.api.zombie.ZombieRegistry;
import cn.enaium.fabricpvz.contentmod.ContentMod;
import cn.enaium.fabricpvz.api.resource.GameArt;
import cn.enaium.fabricpvz.api.resource.ModImages;
import cn.enaium.fabricpvz.contentmod.RedPlantsArt;
import cn.enaium.fabricpvz.portable.lawn.AlmanacPage;
import cn.enaium.fabricpvz.portable.lawn.Board;
import cn.enaium.fabricpvz.portable.lawn.ChosenSeedState;
import cn.enaium.fabricpvz.portable.lawn.GameConstants;
import cn.enaium.fabricpvz.portable.lawn.GameMode;
import cn.enaium.fabricpvz.portable.lawn.GameScenes;
import cn.enaium.fabricpvz.portable.lawn.LawnApp;
import cn.enaium.fabricpvz.portable.lawn.Plant;
import cn.enaium.fabricpvz.portable.lawn.PlantDefinition;
import cn.enaium.fabricpvz.portable.lawn.PlantPriority;
import cn.enaium.fabricpvz.portable.lawn.PlantingReason;
import cn.enaium.fabricpvz.portable.lawn.PlantWeapon;
import cn.enaium.fabricpvz.portable.lawn.Projectile;
import cn.enaium.fabricpvz.portable.lawn.ReanimationID;
import cn.enaium.fabricpvz.portable.lawn.Resources;
import cn.enaium.fabricpvz.portable.lawn.SeedPacket;
import cn.enaium.fabricpvz.portable.lawn.SeedType;
import cn.enaium.fabricpvz.portable.lawn.StoreItem;
import cn.enaium.fabricpvz.portable.lawn.Zombie;
import cn.enaium.fabricpvz.portable.lawn.ZombieType;
import cn.enaium.fabricpvz.portable.lawn.system.SaveGame;
import cn.enaium.fabricpvz.portable.lawn.widget.AlmanacDialog;
import cn.enaium.fabricpvz.portable.lawn.widget.ChosenSeed;
import cn.enaium.fabricpvz.portable.lawn.widget.SeedChooserScreen;
import cn.enaium.fabricpvz.portable.pvzp.reanim.Reanimation;
import cn.enaium.fabricpvz.portable.pvzp.reanim.ReanimatorTrackInstance;
import cn.enaium.fabricpvz.portable.sexy.Common;
import cn.enaium.fabricpvz.portable.sexy.Point;
import cn.enaium.fabricpvz.portable.sexy.Rect;
import cn.enaium.fabricpvz.portable.sexy.graphics.Graphics;
import cn.enaium.fabricpvz.portable.sexy.graphics.Image;
import cn.enaium.fabricpvz.portable.sexy.graphics.MemoryImage;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The game entry point of the end-to-end run: Fabric Loader starts this class instead of the real
 * {@code Main} ({@code -Dpvz.game.mainClass}), so everything below runs inside the loader's class
 * loader with the mods of a real {@code mods/} directory already resolved, their entry points run
 * and their Mixin configurations applied.
 *
 * <p>Because the loader defines its own copies of the game, the API and the mod classes, this class
 * cannot report to the test through memory: every observation is appended to the file named by
 * {@code -Dpvz.test.report} and the JUnit side asserts on it. The report is rewritten after every
 * phase, so a failure still shows how far the run got.
 *
 * <p>Phases:
 * <ol>
 *   <li>what the loader loaded: its version, the game version, every mod id, this mod;</li>
 *   <li>the mod's registration, as the game's own definition table sees it;</li>
 *   <li>the mod's art: the packet image loaded from the mod jar, bound into the game's resource
 *       table, and re-installed by the api-resource module after a resource group load;</li>
 *   <li>a real headless board: the seed's unlock gate, the seed chooser's second page, the almanac's
 *       second plant page, the plant placed with its recoloured retail art, and the fire pea it fires
 *       at a zombie.</li>
 * </ol>
 */
public final class ContentModGameMain {

    /** Row the plant is placed in. */
    private static final int PLANT_ROW = 2;

    /** Column the plant is placed in. */
    private static final int PLANT_COLUMN = 1;

    /** Row the upgrade phase's four squares sit in. */
    private static final int UPGRADE_ROW = 2;

    /** Square of the upgrade phase's control: a retail repeater the shop's gatling pea upgrades. */
    private static final int UPGRADE_RETAIL_COLUMN = 1;

    /** Square of the reported pair: a red repeater the red gatling pea upgrades. */
    private static final int UPGRADE_RED_COLUMN = 3;

    /** Square holding a retail repeater the red gatling pea upgrades as well. */
    private static final int UPGRADE_RETAIL_TARGET_COLUMN = 5;

    /** Square left empty: an upgrade seed must not be plantable on it. */
    private static final int UPGRADE_EMPTY_COLUMN = 7;

    /** How far into its lane the harness puts the zombie the plant fires at. */
    private static final float ZOMBIE_DISTANCE = 220.0f;

    /** Ticks the harness gives the plant to fire; its launch counter starts at up to 150. */
    private static final int FIRE_TICK_LIMIT = 400;

    /**
     * Ticks a burst measurement runs for, starting from a launch that was forced to happen at once.
     * The last pea of a volley is out by tick ~125 ({@code mLaunchRate - rand(15)} is 135-150, and the
     * repeater's second pea lands at launch counter 25), and the next volley's first pea cannot appear
     * before tick ~160 (the 2nd launch at 135, plus 25/32 ticks for the first pea of the window), so
     * this window captures exactly one volley.
     */
    private static final int BURST_TICK_WINDOW = 140;

    private static final StringBuilder REPORT = new StringBuilder();
    private static String sError = "";

    /** The headless app every phase after the loader phase runs against. */
    private static LawnApp sApp;

    /** Whether the retail assets are present, i.e. whether the reanimation art really loaded. */
    private static boolean sAssetsPresent;

    private ContentModGameMain() {
    }

    public static void main(String[] args) throws Exception {
        Path reportPath = Path.of(requiredProperty("pvz.test.report"));

        try {
            loaderPhase();
            flush(reportPath);

            registrationPhase();
            flush(reportPath);

            assetPhase();
            flush(reportPath);

            // The seed chooser's moment: the mod's packets are drawn with no board and no tick behind
            // them. This is where the player picks seeds, and it is the position the bug of §53 showed
            // up in - a packet that is only drawn here must show the mod's own icon.
            packetPhase();
            flush(reportPath);

            boardPhase();
            flush(reportPath);

            upgradePhase();
            flush(reportPath);

            variantsPhase();
            flush(reportPath);

            saveLoadPhase();
        } catch (Throwable aThrowable) {
            sError = aThrowable.toString();
            aThrowable.printStackTrace();
        } finally {
            report("harnessError", sError.isEmpty() ? "none" : sError);
            flush(reportPath);
        }

        System.out.println("[pvz-content-mod] report written to " + reportPath);
        System.out.println(REPORT);

        if (!sError.isEmpty()) {
            throw new IllegalStateException("the content mod harness failed: " + sError);
        }
    }

    // ---------------------------------------------------------------- phase 1

    /** What the loader resolved, and that the API entry point ran before this class. */
    private static void loaderPhase() {
        FabricLoader aLoader = FabricLoader.getInstance();
        report("loaderVersion", aLoader.getModContainer("fabricloader")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("<missing>"));
        report("gameVersion", aLoader.getRawGameVersion());
        report("environment", aLoader.getEnvironmentType().name().toLowerCase(Locale.ROOT));
        report("development", aLoader.isDevelopmentEnvironment());

        List<String> aModIds = new ArrayList<>();
        for (var aContainer : aLoader.getAllMods()) {
            aModIds.add(aContainer.getMetadata().getId());
        }
        report("modsLoaded", String.join(",", new TreeSet<>(aModIds)));
        report("modCount", aModIds.size());
        report("contentModLoaded", aLoader.isModLoaded(ContentMod.MOD_ID));
        report("plantModuleLoaded", aLoader.isModLoaded("api-plant"));
        report("zombieModuleLoaded", aLoader.isModLoaded("api-zombie"));
        report("eventsModuleLoaded", aLoader.isModLoaded("api-events"));
        report("resourcesModuleLoaded", aLoader.isModLoaded("api-resource"));

        report("apiInitialized", PvzFabricApi.isInitialized());
        report("apiVersion", PvzFabricApi.getVersion());
        // The same version as the loader's own metadata, i.e. the API the run actually loaded.
        report("apiVersionByLoader", aLoader.getModContainer("fabric-api")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("<missing>"));
        report("gameVersionByLoader", aLoader.getModContainer("pvz-portable")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("<missing>"));
        report("apiSeesMod", PvzFabricApi.getModContainer(ContentMod.MOD_ID).isPresent());
        report("contentModEntrypointRan", ContentMod.redPeashooter != null);
    }

    // ---------------------------------------------------------------- phase 2

    /** The registered content, as the game's own tables read it. */
    private static void registrationPhase() {
        report("plantsRegistered", PlantRegistry.count());
        report("zombiesRegistered", ZombieRegistry.count());
        report("redPeashooterRegistered", PlantRegistry.get("red_peashooter").isPresent());

        int aSeedType = ContentMod.redPeashooter.seedType();
        report("redPeashooterSeedType", aSeedType);
        report("seedTypesOutsideUpstream", aSeedType >= SeedType.NUM_SEED_TYPES);
        report("redPeashooterUnlockSeed", ContentMod.redPeashooter.definition().mUnlockSeedType);

        // The game's per-seed definition row, i.e. what the seed packet, the almanac and the
        // planting rules read.
        PlantDefinition aDefinition = Plant.getPlantDefinition(aSeedType);
        report("redPeashooterDefinitionCost", aDefinition.mSeedCost);
        report("redPeashooterDefinitionRefresh", aDefinition.mRefreshTime);
        report("redPeashooterDefinitionLaunchRate", aDefinition.mLaunchRate);
        report("redPeashooterDefinitionReanim", aDefinition.mReanimationType);
        report("redPeashooterDefinitionSubclass", aDefinition.mSubClass);

        report("redPeashooterName", Plant.getNameString(aSeedType, SeedType.SEED_NONE));
        report("redPeashooterTooltip", Plant.getToolTip(aSeedType));

        // The two plants added next to it: the repeater's and the gatling pea's own stats, their
        // reanimations and their burst.
        report("redRepeaterRegistered", PlantRegistry.get(ContentMod.REPEATER_ID).isPresent());
        int aRepeaterSeed = ContentMod.redRepeater.seedType();
        report("redRepeaterSeedType", aRepeaterSeed);
        report("redRepeaterUnlockSeed", ContentMod.redRepeater.definition().mUnlockSeedType);
        PlantDefinition aRepeaterDefinition = Plant.getPlantDefinition(aRepeaterSeed);
        report("redRepeaterDefinitionCost", aRepeaterDefinition.mSeedCost);
        report("redRepeaterDefinitionRefresh", aRepeaterDefinition.mRefreshTime);
        report("redRepeaterDefinitionLaunchRate", aRepeaterDefinition.mLaunchRate);
        report("redRepeaterDefinitionReanim", aRepeaterDefinition.mReanimationType);
        report("redRepeaterBurst", ContentMod.redRepeater.definition().mBurst);
        report("redRepeaterName", Plant.getNameString(aRepeaterSeed, SeedType.SEED_NONE));

        report("redGatlingRegistered", PlantRegistry.get(ContentMod.GATLING_ID).isPresent());
        int aGatlingSeed = ContentMod.redGatling.seedType();
        report("redGatlingSeedType", aGatlingSeed);
        report("redGatlingUnlockSeed", ContentMod.redGatling.definition().mUnlockSeedType);
        PlantDefinition aGatlingDefinition = Plant.getPlantDefinition(aGatlingSeed);
        report("redGatlingDefinitionCost", aGatlingDefinition.mSeedCost);
        report("redGatlingDefinitionRefresh", aGatlingDefinition.mRefreshTime);
        report("redGatlingDefinitionLaunchRate", aGatlingDefinition.mLaunchRate);
        report("redGatlingDefinitionReanim", aGatlingDefinition.mReanimationType);
        report("redGatlingBurst", ContentMod.redGatling.definition().mBurst);
        report("redGatlingName", Plant.getNameString(aGatlingSeed, SeedType.SEED_NONE));

        // The twin definitions the mod copies are untouched.
        report("repeaterDefinitionCost", Plant.getPlantDefinition(SeedType.SEED_REPEATER).mSeedCost);
        report("repeaterDefinitionRefresh", Plant.getPlantDefinition(SeedType.SEED_REPEATER).mRefreshTime);
        report("gatlingDefinitionCost", Plant.getPlantDefinition(SeedType.SEED_GATLINGPEA).mSeedCost);
        report("gatlingDefinitionRefresh", Plant.getPlantDefinition(SeedType.SEED_GATLINGPEA).mRefreshTime);

        // Upstream content is untouched: the peashooter's and the snow pea's own rows still read what
        // they always did.
        report("peashooterDefinitionCost", Plant.getPlantDefinition(SeedType.SEED_PEASHOOTER).mSeedCost);
        report("peashooterDefinitionLaunchRate", Plant.getPlantDefinition(SeedType.SEED_PEASHOOTER).mLaunchRate);
        report("snowPeaDefinitionCost", Plant.getPlantDefinition(SeedType.SEED_SNOWPEA).mSeedCost);
    }

    // ---------------------------------------------------------------- phase 3

    /**
     * The app the rest of the run uses, and the app-level dispatch of {@link GameLifecycleEvents}:
     * the mod's art hooks run against a real {@code LawnApp} from here on.
     */
    private static void assetPhase() {
        // A real headless app first: the mod's art must be created with the app the game is
        // running, exactly like the real INITIALIZED dispatch does it.
        sApp = new HeadlessLawn.LoadedReanimApp();
        sApp.mGameMode = GameMode.GAMEMODE_ADVENTURE;
        sApp.mGameScene = GameScenes.SCENE_PLAYING;
        HeadlessLawn.initialize(sApp);

        Path aAssets = Path.of(System.getProperty("pvz.test.resources", ""));
        boolean aAssetsPresent = !aAssets.toString().isBlank() && Files.isDirectory(aAssets);
        sAssetsPresent = aAssetsPresent;
        report("assetsPresent", aAssetsPresent);

        // Real-asset check: the retail archive the run draws from must actually be there (the packet
        // art the mod's icons are made of is rendered out of it).
        if (aAssetsPresent) {
            try {
                cn.enaium.fabricpvz.portable.sexy.pak.PakInterface aPak =
                        cn.enaium.fabricpvz.portable.sexy.pak.PakInterface.getInstance();
                aPak.setResourceFolder(aAssets.toString());
                aPak.addPakFile(aAssets.resolve("main.pak"));
                report("retailPakReadable", aPak.exists("properties/resources.xml"));
            } catch (Throwable aThrowable) {
                report("retailPakError", aThrowable.toString());
            }
        }

        // The game fires this at the end of SexyAppBase::Init, through the API's AppHooksBridge.
        GameLifecycleEvents.INITIALIZED.invoker().onInitialize();
    }

    // ---------------------------------------------------------------- phase 4

    /**
     * A real headless board: the seed's unlock gate, the chooser's second page, the plant with its
     * recoloured retail art and the fire pea it fires.
     */
    private static void boardPhase() {
        LawnApp aApp = sApp;
        // A first-time player: level 1, adventure not finished, so the Snow Pea - and with it the Red
        // Peashooter - is not unlocked yet.
        aApp.mPlayerInfo.mLevel = 1;
        aApp.mPlayerInfo.mFinishedAdventure = 0;
        Common.sRand(0x5EED);

        // The headless fixture has just bound a 1x1 stub for every image id, which is exactly what a
        // resource group load does to the ids it owns. Now run the game's own extractor for a group
        // name (whether or not this machine's resources manifest resolves it - the report carries the
        // answer): the mod's icon lives in the art supplier, not in that table, so it must survive.
        String aGroupError = "none";
        Boolean aGroupResult = null;
        try {
            aGroupResult = Resources.extractResourcesByName(aApp.mResourceManager, "DelayLoad_AwardScreen");
        } catch (Throwable aThrowable) {
            aGroupError = aThrowable.toString();
        }
        report("resourceGroupLoadResult", aGroupResult);
        report("resourceGroupLoadError", aGroupError);
        report("packetIconSurvivesGroupLoad", RedPlantsArt.packetImage(ContentMod.PEASHOOTER_ID) != null);
        report("packetRedPixelsAfterGroupLoad", countRed(drawPacket(ContentMod.redPeashooter.seedType())));

        Board aBoard = new Board(aApp);
        aBoard.initLevel();
        report("boardLevel", aBoard.mLevel);
        report("boardWaves", aBoard.mNumWaves);

        int aSeedType = ContentMod.redPeashooter.seedType();

        // The unlock gate: the registered plant is gated behind the Snow Pea, which the game's own
        // HasSeedType answers for, so the seed is out of reach until that award is the player's.
        report("snowPeaAvailableBefore", aApp.hasSeedType(SeedType.SEED_SNOWPEA));
        report("redPeashooterLockedBefore", !aApp.hasSeedType(aSeedType));

        aApp.mPlayerInfo.mFinishedAdventure = 1;
        report("snowPeaAvailableAfter", aApp.hasSeedType(SeedType.SEED_SNOWPEA));
        report("redPeashooterUnlockedAfter", aApp.hasSeedType(aSeedType));

        chooserPhase(aApp, aBoard, aSeedType);

        // The plant itself: the retail peashooter's skeleton, stats and art.
        Plant aPlant = aBoard.addPlant(PLANT_COLUMN, PLANT_ROW, aSeedType, SeedType.SEED_NONE);
        report("redPeashooterPlaced", aPlant != null);
        report("redPeashooterSeedTypeMatches", aPlant.mSeedType == aSeedType);
        report("redPeashooterHealth", aPlant.mPlantHealth);
        report("redPeashooterMaxHealth", aPlant.mPlantMaxHealth);
        report("redPeashooterSubclass", aPlant.mSubclass);
        report("redPeashooterLaunchRate", aPlant.mLaunchRate);
        report("redPeashooterBodyReanimLoaded", aPlant.mBodyReanimID != ReanimationID.REANIMATIONID_NULL);
        report("redPeashooterAttackRect", rectOf(aPlant.getPlantAttackRect(PlantWeapon.WEAPON_PRIMARY)));
        report("upstreamPeashooterAttackRect",
                rectOf(aBoard.addPlant(0, 4, SeedType.SEED_PEASHOOTER, SeedType.SEED_NONE)
                        .getPlantAttackRect(PlantWeapon.WEAPON_PRIMARY)));

        // The recolouring the mod's onPlanted callback did: every art-carrying track of the plant's
        // body and head reanimations now draws a red copy of the retail image (the head one is the
        // one the plant family owns, and the mod creates it because the game only does that for its
        // own seed types).
        report("redPeashooterPlants", RedPlantsArt.plantedPlants);
        report("redPeashooterRecolours", RedPlantsArt.recolours);
        report("redPeashooterHeadReanim", aPlant.mHeadReanimID != ReanimationID.REANIMATIONID_NULL);
        report("redPeashooterHeadAlpha", overrideAlpha(aPlant, aPlant.mHeadReanimID, "anim_face"));
        report("redPeashooterBodyLeafAlpha",
                overrideAlpha(aPlant, aPlant.mBodyReanimID, "frontleaf"));

        // The icon the mod built: the retail peashooter's own packet art with its colours re-mapped
        // (GameArt.packetArt is what the mod renders it from, at that seed's own placement). Comparing
        // the two masks proves the icon depicts the peashooter and nothing else - which is what a
        // hand-rendered copy of it got wrong once (docs/VERIFICATION.md §46) - and the changed-pixel
        // count proves it was recoloured.
        Image aPacket = RedPlantsArt.packetImage(ContentMod.PEASHOOTER_ID);
        int[] aCompared = compareWithRetailArt(aPacket, SeedType.SEED_PEASHOOTER);
        report("packetOpaquePixels", aCompared[0]);
        report("retailOpaquePixels", aCompared[1]);
        report("packetMaskAgreementPercent", aCompared[2]);
        report("packetRecolouredPixels", aCompared[3]);

        // The frame the cursor preview draws (the game's plant-frame cache, indexed by seed type): a
        // planted plant is red because the mod replaces its reanimation's track images, and the cached
        // frame is rendered from the reanimation itself, so the mod has to put its own red frame there
        // or the plant under the mouse pointer is the retail green one.
        MemoryImage aCachedFrame = aApp.mReanimatorCache.mPlantImages[aSeedType];
        report("cachedPlantFrameSet", aCachedFrame != null);
        report("cachedPlantFrameIsRed", aCachedFrame != null && isRedFrame(aCachedFrame));

        // The almanac last: its preview plant is a second registration-driven plant, and the counters
        // above report the one placed on the board, so the page is opened after they are read.
        almanacPhase(aApp, aSeedType);

        firePhase(aBoard, aPlant);

        // The art supplier is asked once per draw, so neither a tick nor the module's resource group
        // load can take the icon away: the packet the game draws still shows it.
        LawnEvents.TICK.invoker().onTick(aBoard, 1);
        report("packetRedPixelsAfterTickHook", countRed(drawPacket(aSeedType)));

        report("scene", aApp.mGameScene == GameScenes.SCENE_PLAYING ? "PLAYING" : String.valueOf(aApp.mGameScene));
    }

    // ---------------------------------------------------------------- phase 4b

    /**
     * The user's report: a Red Gatling Pea could not be planted on a repeater as its upgrade, although
     * the shop's gatling pea can be planted on one. Every scenario below goes through the game's own
     * path - the seed packet is picked up and dropped with {@code Board::MouseDown}, which is where an
     * upgrade's original plant dies and the new one is added - so the replacement is the game's doing,
     * not a harness re-implementation of it.
     *
     * <p>The retail pair runs first as the control: same path, same two questions, and both answers
     * come from the game's own hard-coded pair, so they hold whatever the mod does. Then the reported
     * pair (red gatling on red repeater), the red gatling on a retail repeater, and an empty square -
     * which takes neither upgrade seed.
     */
    private static void upgradePhase() {
        LawnApp aApp = sApp;
        Common.sRand(0x5EED);

        // A player who owns the seeds the mod gates its two plants behind, so the packets are the ones
        // the game itself would hand out; the planting path below reads no other unlock state.
        aApp.mPlayerInfo.mFinishedAdventure = 1;
        aApp.mPlayerInfo.mPurchases[StoreItem.STORE_ITEM_PLANT_GATLINGPEA] = 1;

        Board aBoard = new Board(aApp);
        aBoard.initLevel();
        aBoard.mSunMoney = 5000;  // the seeds planted here cost 200-275 sun each

        int aRedRepeater = ContentMod.redRepeater.seedType();
        int aRedGatling = ContentMod.redGatling.seedType();

        // 1) The control: the shop's gatling pea on a retail repeater. Both answers are the game's own
        //    hard-coded pair, and the planting replaces the repeater exactly as it always did.
        Plant aRetailRepeater = aBoard.addPlant(UPGRADE_RETAIL_COLUMN, UPGRADE_ROW,
                SeedType.SEED_REPEATER, SeedType.SEED_NONE);
        report("retailGatlingOnRetailRepeaterPlaced", aRetailRepeater != null);
        report("retailGatlingOnRetailRepeaterUpgradable",
                aRetailRepeater != null && aRetailRepeater.isUpgradableTo(SeedType.SEED_GATLINGPEA));
        report("retailGatlingOnRetailRepeaterCanPlant",
                plantingReason(aBoard.canPlantAt(UPGRADE_RETAIL_COLUMN, UPGRADE_ROW, SeedType.SEED_GATLINGPEA)));
        Plant aRetailPlanted = plantOnSquare(aBoard, UPGRADE_RETAIL_COLUMN, UPGRADE_ROW,
                SeedType.SEED_GATLINGPEA);
        report("retailGatlingReplacesRetailRepeater",
                aRetailPlanted != null && aRetailPlanted.mSeedType == SeedType.SEED_GATLINGPEA
                        && aRetailRepeater != null && aRetailRepeater.mDead);

        // 2) The reported pair: the red gatling pea on a red repeater.
        Plant aRedRepeaterPlant = aBoard.addPlant(UPGRADE_RED_COLUMN, UPGRADE_ROW, aRedRepeater,
                SeedType.SEED_NONE);
        report("redGatlingOnRedRepeaterPlaced", aRedRepeaterPlant != null);
        report("redGatlingOnRedRepeaterUpgradable",
                aRedRepeaterPlant != null && aRedRepeaterPlant.isUpgradableTo(aRedGatling));
        report("redGatlingOnRedRepeaterCanPlant",
                plantingReason(aBoard.canPlantAt(UPGRADE_RED_COLUMN, UPGRADE_ROW, aRedGatling)));
        Plant aRedPlanted = plantOnSquare(aBoard, UPGRADE_RED_COLUMN, UPGRADE_ROW, aRedGatling);
        report("redGatlingReplacesRedRepeaterSeedType", aRedPlanted == null ? -1 : aRedPlanted.mSeedType);
        report("redGatlingReplacesRedRepeater", aRedPlanted != null && aRedPlanted.mSeedType == aRedGatling);
        report("redGatlingReplacesRedRepeaterOriginalDead",
                aRedRepeaterPlant != null && aRedRepeaterPlant.mDead);

        // 3) The retail repeater is a legal target as well: the red gatling is that plant's red twin.
        Plant aRetailTarget = aBoard.addPlant(UPGRADE_RETAIL_TARGET_COLUMN, UPGRADE_ROW,
                SeedType.SEED_REPEATER, SeedType.SEED_NONE);
        report("redGatlingOnRetailRepeaterPlaced", aRetailTarget != null);
        report("redGatlingOnRetailRepeaterUpgradable",
                aRetailTarget != null && aRetailTarget.isUpgradableTo(aRedGatling));
        report("redGatlingOnRetailRepeaterCanPlant",
                plantingReason(aBoard.canPlantAt(UPGRADE_RETAIL_TARGET_COLUMN, UPGRADE_ROW, aRedGatling)));
        Plant aRetailTargetReplaced = plantOnSquare(aBoard, UPGRADE_RETAIL_TARGET_COLUMN, UPGRADE_ROW,
                aRedGatling);
        report("redGatlingReplacesRetailRepeater",
                aRetailTargetReplaced != null && aRetailTargetReplaced.mSeedType == aRedGatling
                        && aRetailTarget != null && aRetailTarget.mDead);

        // 4) An empty square takes neither: an upgrade seed needs the plant it upgrades, which is the
        //    retail gatling pea's own behaviour.
        report("redGatlingOnEmptySquareCanPlant",
                plantingReason(aBoard.canPlantAt(UPGRADE_EMPTY_COLUMN, UPGRADE_ROW, aRedGatling)));
        report("retailGatlingOnEmptySquareCanPlant",
                plantingReason(aBoard.canPlantAt(UPGRADE_EMPTY_COLUMN, UPGRADE_ROW, SeedType.SEED_GATLINGPEA)));
        report("redGatlingOnEmptySquareStaysEmpty",
                plantOnSquare(aBoard, UPGRADE_EMPTY_COLUMN, UPGRADE_ROW, aRedGatling) == null);
    }

    /**
     * Plants one seed type on one square the way a player does it: a real seed-bank packet is picked
     * up and dropped on the square through {@code Board::MouseDown} - first the click on the packet
     * ({@code SeedPacket::MouseDown}, which puts the seed in the cursor), then the click on the lawn
     * ({@code BoardInput}'s planting branch, which asks {@code Board::CanPlantAt}, kills the plant an
     * upgrade replaces and adds the new one).
     *
     * <p>The packet is the game's own bank packet: the harness only hands it the seed type and the
     * refresh a player would have waited out, and gives the bank the width its retail image has (the
     * headless fixture binds 1x1 stubs, so the bank's own hit-test gate would never open otherwise).
     *
     * @return the plant the square holds afterwards, or {@code null} when nothing was planted
     */
    private static Plant plantOnSquare(Board aBoard, int theColumn, int theRow, int theSeedType) {
        aBoard.mSeedBank.mApp = aBoard.mApp;
        aBoard.mSeedBank.mBoard = aBoard;
        aBoard.mSeedBank.mWidth = 200;

        SeedPacket aPacket = aBoard.mSeedBank.mSeedPackets[0];
        aPacket.mApp = aBoard.mApp;
        aPacket.mBoard = aBoard;
        aPacket.mIndex = 0;
        aPacket.setPacketType(theSeedType, SeedType.SEED_NONE);
        // The refresh an upgrade packet needs before a player may pick it up again (3.5 s for the shop
        // plant's own): the harness has no clock, so it hands the packet back the way
        // SeedPacket::Update does when the refresh counter runs out.
        aPacket.mActive = true;
        aBoard.mSeedBank.mNumPackets = 1;

        aBoard.mouseDown(aPacket.mX + 10, aPacket.mY + 30, 1);
        aBoard.mouseDown(aBoard.gridToPixelX(theColumn, theRow) + 40,
                aBoard.gridToPixelY(theColumn, theRow) + 40, 1);
        return aBoard.getTopPlantAt(theColumn, theRow, PlantPriority.TOPPLANT_ONLY_NORMAL_POSITION);
    }

    // ---------------------------------------------------------------- phase 5

    /**
     * The user's report: a Red Peashooter saved mid-level came back looking like the retail green
     * peashooter. A board with one is saved, a fresh board loads the file, and the plant that comes
     * back must still draw the red art the mod painted on it and still fire its fire pea.
     *
     * <p>{@code Board::loadGame} restores the plants without going through
     * {@code Plant::PlantInitialize}, and the save carries the reanimations but not the per-track
     * {@code mImageOverride} (the mod's red copies are runtime images with no resource id), so the
     * art is only rebuilt because the API dispatches {@code onRestored} when the load succeeds.
     */
    private static void saveLoadPhase() throws Exception {
        LawnApp aApp = sApp;
        Common.sRand(0x5EED);

        int aSeedType = ContentMod.redPeashooter.seedType();

        Board aSaved = new Board(aApp);
        aSaved.initLevel();
        Plant aPlaced = aSaved.addPlant(PLANT_COLUMN, PLANT_ROW, aSeedType, SeedType.SEED_NONE);
        report("reloadSavedPlantPlaced", aPlaced != null);
        // Saved injured, so the assertions below also prove the load does not heal it.
        aPlaced.mPlantHealth = 111;
        aPlaced.mPlantMaxHealth = 300;

        Path aFile = Files.createTempFile("pvz-content-reload", ".v4");
        report("reloadSaved", SaveGame.lawnSaveGame(aSaved, aFile.toString()));

        // A fresh board of the same app loads the file; nothing of the saved plant is in place, so
        // every assertion below proves the load restored it.
        Board aLoaded = new Board(aApp);
        aLoaded.initLevel();
        report("reloadLoaded", aLoaded.loadGame(aFile.toString()));
        Files.deleteIfExists(aFile);

        Plant aRestored = null;
        for (Plant aPlant : aLoaded.mPlants) {
            if (!aPlant.mDead && aPlant.mSeedType == aSeedType) {
                aRestored = aPlant;
                break;
            }
        }
        report("reloadPlantFound", aRestored != null);

        if (aRestored == null) {
            report("reloadTracksRepainted", -1);
            report("reloadHeadRepainted", -1);
            report("reloadHealth", -1);
            report("reloadFireProjectileTypes", "-1");
            return;
        }

        // The per-track art the mod re-painted in onRestored: the body's leaf track and the head's
        // face track now draw a red copy again, and the injured health is untouched.
        report("reloadHealth", aRestored.mPlantHealth);
        report("reloadTracksRepainted", countImageOverrides(aApp, aRestored.mBodyReanimID));
        report("reloadHeadRepainted", countImageOverrides(aApp, aRestored.mHeadReanimID));

        // The behaviour is untouched by the art loss, which is what the user saw: the restored plant
        // still fires its fire pea. The fire path reads the reanimation's pea-head offset, so it needs
        // the retail reanimations.
        if (!sAssetsPresent) {
            report("reloadZombieSpawned", false);
            report("reloadFireProjectileTypes", "-1");
            return;
        }

        Zombie aZombie = aLoaded.addZombieInRow(ZombieType.ZOMBIE_NORMAL, PLANT_ROW, 0);
        if (aZombie == null) {
            report("reloadZombieSpawned", false);
            report("reloadFireProjectileTypes", "-1");
            return;
        }
        report("reloadZombieSpawned", true);
        aZombie.mPosX = aRestored.mX + ZOMBIE_DISTANCE;
        aZombie.mX = (int) aZombie.mPosX;
        aZombie.mY = (int) aZombie.mPosY;

        int aBefore = aLoaded.mProjectiles.mSize;
        for (int aTick = 0; aTick < FIRE_TICK_LIMIT; aTick++) {
            aRestored.update();
            if (aLoaded.mProjectiles.mSize > aBefore) {
                break;
            }
        }

        StringBuilder aTypes = new StringBuilder();
        for (Projectile aProjectile : aLoaded.mProjectiles) {
            if (!aTypes.isEmpty()) {
                aTypes.append(',');
            }
            aTypes.append(aProjectile.mProjectileType);
        }
        report("reloadFireProjectileTypes", aTypes.isEmpty() ? "-1" : aTypes.toString());
    }

    // ---------------------------------------------------------------- phase 6

    /**
     * The two plants added next to the Red Peashooter: their unlock gates ("obtained the Repeater" /
     * "bought the shop's Gatling Pea"), the multi-shot behaviour the {@code burst} API gives them, and
     * their red art (board reanimations, cached frames and packet icons).
     */
    private static void variantsPhase() {
        LawnApp aApp = sApp;
        Common.sRand(0x5EED);

        // A first-time player: neither the Repeater's adventure award nor the shop's Gatling Pea is
        // the player's, so both red twins are out of reach.
        aApp.mPlayerInfo.mLevel = 1;
        aApp.mPlayerInfo.mFinishedAdventure = 0;
        aApp.mPlayerInfo.mPurchases[StoreItem.STORE_ITEM_PLANT_GATLINGPEA] = 0;
        report("redRepeaterLockedBefore", !aApp.hasSeedType(ContentMod.redRepeater.seedType()));
        report("redGatlingLockedBefore", !aApp.hasSeedType(ContentMod.redGatling.seedType()));

        // The adventure's award unlocks the Repeater and with it the red one; the shop item is still
        // missing, so the gatling twin stays locked.
        aApp.mPlayerInfo.mFinishedAdventure = 1;
        report("repeaterAvailableAfterAdventure", aApp.hasSeedType(SeedType.SEED_REPEATER));
        report("redRepeaterUnlockedAfterAdventure", aApp.hasSeedType(ContentMod.redRepeater.seedType()));
        report("redGatlingStillLocked", !aApp.hasSeedType(ContentMod.redGatling.seedType()));

        // Buying the shop's Gatling Pea releases the red twin through the game's own HasSeedType: the
        // mod itself never enters the shop.
        aApp.mPlayerInfo.mPurchases[StoreItem.STORE_ITEM_PLANT_GATLINGPEA] = 1;
        report("gatlingAvailableAfterPurchase", aApp.hasSeedType(SeedType.SEED_GATLINGPEA));
        report("redGatlingUnlockedAfterPurchase", aApp.hasSeedType(ContentMod.redGatling.seedType()));

        burstPhase(aApp, ContentMod.redPeashooter.seedType(), "redPeashooter");
        burstPhase(aApp, ContentMod.redRepeater.seedType(), "redRepeater");
        burstPhase(aApp, ContentMod.redGatling.seedType(), "redGatling");

        variantArtPhase(aApp);
        variantPacketPhase();
    }

    /**
     * Places one registered plant next to a zombie and counts the fire peas it fires in one volley,
     * starting the volley at once ({@code mLaunchCounter = 0}) so the window is deterministic. The
     * expected counts are the twin's own: one pea for the peashooter, two for the repeater, four for
     * the gatling pea - the numbers the game hard-codes on its own seed types and the {@code burst}
     * API reproduces.
     */
    private static void burstPhase(LawnApp aApp, int theSeedType, String theKey) {
        Board aBoard = new Board(aApp);
        aBoard.initLevel();

        Plant aPlant = aBoard.addPlant(PLANT_COLUMN, PLANT_ROW, theSeedType, SeedType.SEED_NONE);
        Zombie aZombie = aPlant == null ? null
                : aBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, PLANT_ROW, 0);
        if (aPlant == null || aZombie == null) {
            report(theKey + "VolleyPlaced", false);
            report(theKey + "VolleyProjectiles", -1);
            return;
        }

        report(theKey + "VolleyPlaced", true);
        aZombie.mPosX = aPlant.mX + ZOMBIE_DISTANCE;
        aZombie.mX = (int) aZombie.mPosX;
        aZombie.mY = (int) aZombie.mPosY;
        report(theKey + "VolleyTargetFound", aPlant.findTargetZombie(PLANT_ROW, PlantWeapon.WEAPON_PRIMARY) != null);

        aPlant.mLaunchCounter = 0;
        int aBefore = aBoard.mProjectiles.mSize;
        for (int aTick = 0; aTick < BURST_TICK_WINDOW; aTick++) {
            aPlant.update();
        }

        report(theKey + "VolleyProjectiles", aBoard.mProjectiles.mSize - aBefore);
        StringBuilder aTypes = new StringBuilder();
        for (Projectile aProjectile : aBoard.mProjectiles) {
            if (!aTypes.isEmpty()) {
                aTypes.append(',');
            }
            aTypes.append(aProjectile.mProjectileType);
        }
        report(theKey + "VolleyProjectileTypes", aTypes.isEmpty() ? "-1" : aTypes.toString());
        report(theKey + "RecolouredTracks", countImageOverrides(aApp, aPlant.mBodyReanimID));
    }

    /**
     * The red art of the two new plants on the board: the body and head reanimations of each carry
     * overrides on the tracks the retail twin's own reanimation has art in ({@code idle_headleaf_*} for
     * the repeater, the barrel tracks for the gatling pea), those overrides are red, and the game's
     * cached plant frame for the registered seed type is the red one.
     */
    private static void variantArtPhase(LawnApp aApp) {
        Board aBoard = new Board(aApp);
        aBoard.initLevel();

        Plant aRepeater = aBoard.addPlant(PLANT_COLUMN, PLANT_ROW, ContentMod.redRepeater.seedType(), SeedType.SEED_NONE);
        Plant aGatling = aBoard.addPlant(PLANT_COLUMN + 2, PLANT_ROW, ContentMod.redGatling.seedType(), SeedType.SEED_NONE);

        report("redRepeaterPlaced", aRepeater != null);
        report("redGatlingPlaced", aGatling != null);

        if (aRepeater != null) {
            report("redRepeaterHeadReanim", aRepeater.mHeadReanimID != ReanimationID.REANIMATIONID_NULL);
            report("redRepeaterBodyTracks", countImageOverrides(aApp, aRepeater.mBodyReanimID));
            report("redRepeaterHeadTracks", countImageOverrides(aApp, aRepeater.mHeadReanimID));
            Image aHeadleaf = trackOverride(aRepeater, "idle_headleaf_farthest");
            report("redRepeaterHeadleafOverride", aHeadleaf != null);
            report("redRepeaterHeadleafRed", isRedImage(aHeadleaf));
        }

        if (aGatling != null) {
            report("redGatlingHeadReanim", aGatling.mHeadReanimID != ReanimationID.REANIMATIONID_NULL);
            report("redGatlingBodyTracks", countImageOverrides(aApp, aGatling.mBodyReanimID));
            report("redGatlingHeadTracks", countImageOverrides(aApp, aGatling.mHeadReanimID));
            Image aBarrel = trackOverride(aGatling, "GatlingPea_barrel1");
            report("redGatlingBarrelOverride", aBarrel != null);
            // The barrel/helmet art is metal grey, so the hue mapping leaves its saturation alone; the
            // head is the plant's own green and is what proves the mapping landed on red.
            report("redGatlingFaceRed", isRedImage(trackOverride(aGatling, "anim_face")));
        }

        MemoryImage aRepeaterFrame = aApp.mReanimatorCache.mPlantImages[ContentMod.redRepeater.seedType()];
        report("redRepeaterCachedFrameSet", aRepeaterFrame != null);
        report("redRepeaterCachedFrameIsRed", aRepeaterFrame != null && isRedFrame(aRepeaterFrame));
        MemoryImage aGatlingFrame = aApp.mReanimatorCache.mPlantImages[ContentMod.redGatling.seedType()];
        report("redGatlingCachedFrameSet", aGatlingFrame != null);
        report("redGatlingCachedFrameIsRed", aGatlingFrame != null && isRedFrame(aGatlingFrame));
    }

    /**
     * Each new plant's icon against the retail twin's own packet art: the mask has to agree pixel for
     * pixel (the icon depicts exactly that plant, at that seed's own packet placement) while the
     * colours differ (it was re-mapped to red). The icon is what the plants' art supplier hands the
     * game, i.e. the image the packets of phase 3b were drawn from.
     */
    private static void variantPacketPhase() {
        int[] aRepeater = compareWithRetailArt(RedPlantsArt.packetImage(ContentMod.REPEATER_ID),
                SeedType.SEED_REPEATER);
        report("redRepeaterPacketOpaquePixels", aRepeater[0]);
        report("redRepeaterRetailOpaquePixels", aRepeater[1]);
        report("redRepeaterPacketMaskAgreementPercent", aRepeater[2]);
        report("redRepeaterPacketRecolouredPixels", aRepeater[3]);

        int[] aGatling = compareWithRetailArt(RedPlantsArt.packetImage(ContentMod.GATLING_ID),
                SeedType.SEED_GATLINGPEA);
        report("redGatlingPacketOpaquePixels", aGatling[0]);
        report("redGatlingRetailOpaquePixels", aGatling[1]);
        report("redGatlingPacketMaskAgreementPercent", aGatling[2]);
        report("redGatlingPacketRecolouredPixels", aGatling[3]);
    }

    /**
     * @return the image a named track of a plant's head (or, failing that, body) reanimation draws
     *     instead of its retail one, or {@code null} when the track has no override
     */
    private static Image trackOverride(Plant thePlant, String theTrack) {
        Image anImage = overrideImage(thePlant, thePlant.mHeadReanimID, theTrack);
        return anImage != null ? anImage : overrideImage(thePlant, thePlant.mBodyReanimID, theTrack);
    }

    /** @return the image one track of a plant's reanimation draws instead of its retail one, or null */
    private static Image overrideImage(Plant thePlant, int theReanimID, String theTrack) {
        Reanimation aReanim = thePlant.mApp.reanimationTryToGet(theReanimID);
        return aReanim == null ? null : aReanim.getImageOverride(theTrack);
    }

    /**
     * @param theImage an image
     * @return whether its opaque pixels sit on the red side of the colour wheel, i.e. whether the
     *     mod's recolouring reached it
     */
    private static boolean isRedImage(Image theImage) {
        return theImage instanceof MemoryImage aMemory && isRedFrame(aMemory);
    }

    /** How many tracks of a reanimation currently draw a replaced image. */
    private static int countImageOverrides(LawnApp theApp, int theReanimID) {
        Reanimation aReanim = theApp.reanimationTryToGet(theReanimID);
        if (aReanim == null || aReanim.mTrackInstances == null) {
            return 0;
        }

        int aCount = 0;
        for (ReanimatorTrackInstance aTrack : aReanim.mTrackInstances) {
            if (aTrack.mImageOverride != null) {
                aCount++;
            }
        }
        return aCount;
    }

    /**
     * Builds the seed chooser the way {@code CutScene::StartSeedChooser} does and reports which page
     * the registered seed landed on: the retail grid is page 0, and a registered seed the player may
     * pick gets its own page behind it.
     */
    private static void chooserPhase(LawnApp aApp, Board aBoard, int theSeedType) {
        aBoard.mSeedBank.mNumPackets = 10;  // a level's bank, so a pick may fly into it
        aApp.mBoard = aBoard;
        aBoard.mCutScene.mSeedChoosing = true;

        SeedChooserScreen aChooser = new SeedChooserScreen();
        aApp.mSeedChooserScreen = aChooser;

        report("chooserPages", aChooser.mNumPages);
        report("chooserPage", aChooser.mPage);
        Point aSlotZero = new Point();
        SeedChooserScreen.getSeedPositionInChooser(0, aChooser.has7Rows(), aSlotZero);
        report("chooserFirstPageShowsRedPeashooter",
                aChooser.seedHitTest(aSlotZero.mX + 4, aSlotZero.mY + 4) == theSeedType);
        report("chooserSeedState", aChooser.mChosenSeeds[theSeedType].mSeedState
                == ChosenSeedState.SEED_IN_CHOOSER ? "SEED_IN_CHOOSER" : "other");

        aChooser.switchPage(1);
        report("chooserPageAfterSwitch", aChooser.mPage);
        report("chooserSecondPageSlot0IsRedPeashooter",
                aChooser.seedHitTest(aSlotZero.mX + 4, aSlotZero.mY + 4) == theSeedType);
        ChosenSeed aChosenSeed = aChooser.mChosenSeeds[theSeedType];
        report("chooserSecondPageSeedSquare", aChosenSeed.mX == aSlotZero.mX && aChosenSeed.mY == aSlotZero.mY);

        // Put the board back the way the rest of the phase expects it.
        aChooser.switchPage(0);
        aBoard.mCutScene.mSeedChoosing = false;
        aApp.mSeedChooserScreen = null;
    }

    /**
     * The almanac: the plant page keeps the retail grid as page 0 and puts the registered seed on the
     * page behind it, where its card shows the name and the description the mod registered - both read
     * through the game's own seams ({@code Plant::GetNameString},
     * {@code AlmanacDialog::GetPlantDescriptionText}), which the API's AlmanacMixin injects into.
     */
    private static void almanacPhase(LawnApp aApp, int theSeedType) {
        AlmanacDialog aAlmanac = aApp.doAlmanacDialog(SeedType.SEED_NONE, ZombieType.ZOMBIE_INVALID);
        aAlmanac.setPage(AlmanacPage.ALMANAC_PAGE_PLANTS);

        report("almanacPlantPages", aAlmanac.mPlantNumPages);
        report("almanacPlantPage", aAlmanac.mPlantPage);

        // Slot 0 of page 0 is the peashooter's square, so the registered seed is not on that page.
        Point aSlotZero = new Point();
        aAlmanac.getSeedSlotPosition(0, aSlotZero);
        report("almanacFirstPageSlot0IsRedPeashooter",
                aAlmanac.seedHitTest(aSlotZero.mX + 4, aSlotZero.mY + 4) == theSeedType);

        aAlmanac.switchPlantPage(1);
        report("almanacPlantPageAfterSwitch", aAlmanac.mPlantPage);
        report("almanacSecondPageSlot0IsRedPeashooter",
                aAlmanac.seedHitTest(aSlotZero.mX + 4, aSlotZero.mY + 4) == theSeedType);

        // Clicking its square selects it, the way a player does it.
        aAlmanac.mouseDown(aSlotZero.mX + 4, aSlotZero.mY + 4, 1);
        report("almanacSelectedSeedIsRedPeashooter", aAlmanac.mSelectedSeed == theSeedType);

        // The two texts of its card. The name goes through Plant::GetNameString and the description
        // through the almanac's own seam; both are the mod's strings, not the game's string file.
        report("almanacSelectedPlantName", Plant.getNameString(aAlmanac.mSelectedSeed, SeedType.SEED_NONE));
        PlantDefinition aDefinition = Plant.getPlantDefinition(aAlmanac.mSelectedSeed);
        report("almanacSelectedPlantDescription",
                AlmanacDialog.getPlantDescriptionText(aAlmanac.mSelectedSeed, aDefinition));

        // The card's portrait is a real Plant the almanac builds for the selected seed. Its head is a
        // second reanimation the game creates only for its own pea shooters, so the mod has to supply it
        // here as well - without it the portrait shows leaves and stalk and no head, the same way the
        // cursor preview did (docs/VERIFICATION.md §48).
        Plant aPortrait = aAlmanac.mPlant;
        report("almanacPortraitPlaced", aPortrait != null);
        report("almanacPortraitHeadReanim",
                aPortrait != null && aPortrait.mHeadReanimID != ReanimationID.REANIMATIONID_NULL);

        aApp.killAlmanacDialog();
    }

    /**
     * Puts a zombie into the plant's lane and ticks the plant until it fires, the way the game does
     * it: {@code Plant::Update} drives {@code UpdateShooter}, which asks
     * {@code Plant::FindTargetZombie} and then fires - for a registered plant, through the API's
     * projectile hook.
     *
     * <p>Reports the projectile it fired: the board has none before this phase, so every projectile
     * in it afterwards is the plant's.
     */
    private static void firePhase(Board aBoard, Plant thePlant) {
        Zombie aZombie = aBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, PLANT_ROW, 0);
        if (aZombie == null) {
            report("fireZombieSpawned", false);
            return;
        }

        report("fireZombieSpawned", true);
        // The walker writes mPosX; the rectangles the game tests read mX/mY, which Zombie::Update
        // copies once per tick - this harness ticks the plant, not the zombie, so it copies them here.
        aZombie.mPosX = thePlant.mX + ZOMBIE_DISTANCE;
        aZombie.mX = (int) aZombie.mPosX;
        aZombie.mY = (int) aZombie.mPosY;
        report("fireTargetFound", thePlant.findTargetZombie(PLANT_ROW, PlantWeapon.WEAPON_PRIMARY) != null);

        int aProjectilesBefore = aBoard.mProjectiles.mSize;
        int aTicks = -1;
        for (int aTick = 0; aTick < FIRE_TICK_LIMIT; aTick++) {
            thePlant.update();
            if (aBoard.mProjectiles.mSize > aProjectilesBefore) {
                aTicks = aTick + 1;
                break;
            }
        }

        report("fireTicks", aTicks);
        report("fireProjectileCount", aBoard.mProjectiles.mSize - aProjectilesBefore);

        // The board had no projectile before this phase, so every projectile in it is the one the
        // plant just fired; its type is reported as a list because a projectile id is not an index.
        StringBuilder aTypes = new StringBuilder();
        for (Projectile aProjectile : aBoard.mProjectiles) {
            if (!aTypes.isEmpty()) {
                aTypes.append(',');
            }
            aTypes.append(aProjectile.mProjectileType);
        }
        report("fireProjectileTypes", aTypes.isEmpty() ? "-1" : aTypes.toString());

        // The fired pea carries its body: a fireball is drawn from the reanimation the game attaches
        // (Projectile::Draw has no image for the type, only a shadow), and this is the assertion that
        // a directly fired one is not just a shadow on the lawn - the bug docs/VERIFICATION.md §46
        // records.
        StringBuilder aBody = new StringBuilder();
        for (Projectile aProjectile : aBoard.mProjectiles) {
            Reanimation aFlame =
                    cn.enaium.fabricpvz.portable.pvzp.reanim.Attachment.findReanimAttachment(aProjectile.mAttachmentID);
            if (!aBody.isEmpty()) {
                aBody.append(',');
            }
            aBody.append(aFlame == null ? "-1" : String.valueOf(aFlame.mReanimationType));
        }
        report("fireProjectileBodies", aBody.isEmpty() ? "-1" : aBody.toString());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The alpha of the pixel in the middle of one track's image override, i.e. whether the recolouring
     * produced an image with art in it. 0 when the reanimation or the override is missing.
     */
    private static int overrideAlpha(Plant thePlant, int theReanimID, String theTrack) {
        Reanimation aReanim = thePlant.mApp.reanimationTryToGet(theReanimID);
        if (aReanim == null) {
            return 0;
        }

        Image aImage = aReanim.getImageOverride(theTrack);
        if (!(aImage instanceof MemoryImage aMemory)) {
            return 0;
        }

        int[] aBits = aMemory.getBits();
        return aBits[(aMemory.mHeight / 2) * aMemory.mWidth + aMemory.mWidth / 2] >>> 24;
    }

    private static String sizeOf(Image theImage) {
        return theImage == null ? "missing" : theImage.mWidth + "x" + theImage.mHeight;
    }

    /**
     * Compares one mod packet icon with the retail art it is made of.
     *
     * @param theIcon the image in the mod's packet slot
     * @param theRetailSeedType the retail seed whose packet art the icon copies
     * @return {@code [opaque pixels of the icon, opaque pixels of the retail art, percentage of the
     *     image whose opacity the two agree on, pixels whose colour the recolouring changed]}
     */
    private static int[] compareWithRetailArt(Image theIcon, int theRetailSeedType) {
        MemoryImage aRetail = GameArt.packetArt(theRetailSeedType);
        if (!(theIcon instanceof MemoryImage aIcon) || aRetail == null) {
            return new int[] {-1, -1, -1, -1};
        }

        int[] aIconBits = aIcon.getBits();
        int[] aRetailBits = aRetail.getBits();
        int aCount = Math.min(aIconBits.length, aRetailBits.length);

        int aIconOpaque = 0;
        int aRetailOpaque = 0;
        int aAgree = 0;
        int aChanged = 0;
        for (int i = 0; i < aCount; i++) {
            int anIconAlpha = aIconBits[i] >>> 24;
            int aRetailAlpha = aRetailBits[i] >>> 24;
            if (anIconAlpha != 0) {
                aIconOpaque++;
            }
            if (aRetailAlpha != 0) {
                aRetailOpaque++;
            }
            if ((anIconAlpha == 0) == (aRetailAlpha == 0)) {
                aAgree++;
            }
            if (anIconAlpha != 0 && aIconBits[i] != aRetailBits[i]) {
                aChanged++;
            }
        }

        return new int[] {aIconOpaque, aRetailOpaque, aAgree * 100 / Math.max(aCount, 1), aChanged};
    }


    /**
     * @param theFrame a cached plant frame
     * @return whether the frame's opaque pixels sit on the red side of the colour wheel, i.e. whether
     *     the mod's recolouring reached the frame the cursor preview draws
     */
    private static boolean isRedFrame(MemoryImage theFrame) {
        int[] aBits = theFrame.getBits();
        if (aBits == null) {
            return false;
        }

        float[] anHsb = new float[3];
        int anOpaque = 0;
        int aRed = 0;
        for (int aPixel : aBits) {
            if ((aPixel >>> 24) < 128) {
                continue;
            }
            anOpaque++;
            java.awt.Color.RGBtoHSB((aPixel >> 16) & 0xFF, (aPixel >> 8) & 0xFF, aPixel & 0xFF, anHsb);
            if (anHsb[1] > 0.3f && (anHsb[0] < 0.08f || anHsb[0] > 0.94f)) {
                aRed++;
            }
        }
        return anOpaque > 0 && aRed * 2 > anOpaque;
    }

    private static String pixelAt(Image theImage, int theX, int theY) {
        if (!(theImage instanceof MemoryImage aMemory) || theX >= aMemory.mWidth || theY >= aMemory.mHeight) {
            return "missing";
        }

        int[] aBits = aMemory.getBits();
        return String.format("0x%08X", aBits[theY * aMemory.mWidth + theX]);
    }

    private static String rectOf(Rect theRect) {
        return theRect == null ? "null"
                : theRect.mX + "," + theRect.mY + "," + theRect.mWidth + "," + theRect.mHeight;
    }

    /** One {@code PlantingReason} value by name, so the report reads as the game's own verdict. */
    private static String plantingReason(int theReason) {
        return switch (theReason) {
            case PlantingReason.PLANTING_OK -> "PLANTING_OK";
            case PlantingReason.PLANTING_NOT_HERE -> "PLANTING_NOT_HERE";
            case PlantingReason.PLANTING_ONLY_ON_GRAVES -> "PLANTING_ONLY_ON_GRAVES";
            case PlantingReason.PLANTING_ONLY_IN_POOL -> "PLANTING_ONLY_IN_POOL";
            case PlantingReason.PLANTING_ONLY_ON_GROUND -> "PLANTING_ONLY_ON_GROUND";
            case PlantingReason.PLANTING_NEEDS_POT -> "PLANTING_NEEDS_POT";
            case PlantingReason.PLANTING_NOT_ON_ART -> "PLANTING_NOT_ON_ART";
            case PlantingReason.PLANTING_NOT_PASSED_LINE -> "PLANTING_NOT_PASSED_LINE";
            case PlantingReason.PLANTING_NEEDS_UPGRADE -> "PLANTING_NEEDS_UPGRADE";
            case PlantingReason.PLANTING_NOT_ON_GRAVE -> "PLANTING_NOT_ON_GRAVE";
            case PlantingReason.PLANTING_NOT_ON_CRATER -> "PLANTING_NOT_ON_CRATER";
            case PlantingReason.PLANTING_NOT_ON_WATER -> "PLANTING_NOT_ON_WATER";
            case PlantingReason.PLANTING_NEEDS_GROUND -> "PLANTING_NEEDS_GROUND";
            case PlantingReason.PLANTING_NEEDS_SLEEPING -> "PLANTING_NEEDS_SLEEPING";
            default -> String.valueOf(theReason);
        };
    }

    private static String requiredProperty(String theName) {
        String aValue = System.getProperty(theName, "");
        if (aValue.isBlank()) {
            throw new IllegalStateException("-D" + theName + " is required");
        }
        return aValue;
    }

    // ------------------------------------------- phase 3b: the packets, at the chooser's moment

    /**
     * Draws one seed packet per plant - each registered seed next to the retail twin its art comes from
     * - writes them into {@code build/packet-dump} and reports, per packet, how many of its pixels are
     * red and how many are opaque.
     *
     * <p><b>This is the seed chooser's moment</b>: it runs right after the app exists and before any
     * board is created or ticked, which is where the player picks seeds. The mod's icon is therefore
     * only reachable through the art supplier the plants were registered with; a mechanism that
     * builds it from a tick hook shows the packet background and the cost here and nothing else - the
     * bug of {@code docs/VERIFICATION.md} §53.
     *
     * <p>The number that matters is the red one: the retail peashooter family is green, so a packet
     * that shows this mod's art has red where the retail one has none - and the background is a
     * green/beige gradient with no red in it, so the count is not confounded by it.
     */
    private static void packetPhase() throws Exception {
        Path aDir = Path.of(requiredProperty("pvz.test.report")).getParent().resolve("packet-dump");
        Files.createDirectories(aDir);

        int[] aSeeds = {
            SeedType.SEED_PEASHOOTER, SeedType.SEED_REPEATER, SeedType.SEED_GATLINGPEA,
            ContentMod.redPeashooter.seedType(), ContentMod.redRepeater.seedType(),
            ContentMod.redGatling.seedType(),
        };
        String[] aNames = {
            "retail_peashooter", "retail_repeater", "retail_gatling",
            "red_peashooter", "red_repeater", "red_gatling",
        };
        for (int i = 0; i < aSeeds.length; i++) {
            MemoryImage aPacket = drawPacket(aSeeds[i]);
            report(aNames[i] + "PacketRedPixels", countRed(aPacket));
            report(aNames[i] + "PacketOpaquePixels", countOpaque(aPacket));
            writePng(aDir.resolve(aNames[i] + ".png"), aPacket);
        }

        // The icon each registered plant's art supplier hands the game, counted the same way: the
        // packet above must be that very image (nothing else in the packet is red), so the two counts
        // say the game really drew the mod's icon and not some other path to a plant of this seed.
        report("red_peashooterIconRedPixels", countRed(iconOf(ContentMod.PEASHOOTER_ID)));
        report("red_repeaterIconRedPixels", countRed(iconOf(ContentMod.REPEATER_ID)));
        report("red_gatlingIconRedPixels", countRed(iconOf(ContentMod.GATLING_ID)));

        // The icon the game drew: the image the art supplier handed over, at the size a fixed packet
        // art has to be, built here with no board and no tick behind it.
        Image aIcon = RedPlantsArt.packetImage(ContentMod.PEASHOOTER_ID);
        report("packetSize", sizeOf(aIcon));
        report("packetCentrePixel", pixelAt(aIcon, 25, 35));
        report("packetPlantPixel", pixelAt(aIcon, 20, 25));
        report("assetLoadError", RedPlantsArt.loadError.isEmpty() ? "none" : RedPlantsArt.loadError);

        // The purple (upgrade) packet background is the game's own answer to {@code Plant::IsUpgrade},
        // which the API's PlantMixin routes to the registration: only the gatling twin declares it, and
        // the retail gatling pea - the plant it copies - is a shop upgrade too.
        report("plantIsUpgradeRedGatling", Plant.isUpgrade(ContentMod.redGatling.seedType()));
        report("plantIsUpgradeRedRepeater", Plant.isUpgrade(ContentMod.redRepeater.seedType()));
        report("plantIsUpgradeRedPeashooter", Plant.isUpgrade(ContentMod.redPeashooter.seedType()));
        report("plantIsUpgradeRetailGatling", Plant.isUpgrade(SeedType.SEED_GATLINGPEA));
    }

    /**
     * @param thePlantId a registered red plant
     * @return its art supplier's icon, or an empty image while it could not be built (so the pixel
     *     counters report zero instead of the harness throwing)
     */
    private static MemoryImage iconOf(String thePlantId) {
        Image anIcon = RedPlantsArt.packetImage(thePlantId);
        return anIcon instanceof MemoryImage aMemory ? aMemory
                : ModImages.blank(GameConstants.SEED_PACKET_WIDTH, GameConstants.SEED_PACKET_HEIGHT);
    }

    /** One seed type's 50x70 seed packet, drawn exactly as the chooser draws its own packet widget. */
    private static MemoryImage drawPacket(int theSeedType) {
        MemoryImage aPacket = ModImages.blank(GameConstants.SEED_PACKET_WIDTH, GameConstants.SEED_PACKET_HEIGHT);
        Graphics aGraphics = new Graphics(aPacket);
        aGraphics.setLinearBlend(true);
        SeedPacket.drawSeedPacket(aGraphics, 0, 0, theSeedType, SeedType.SEED_NONE, 0.0f, 255, true, false);
        return aPacket;
    }

    /** Opaque pixels whose hue is red-orange: the mod's recolouring, and (almost) nothing else. */
    private static int countRed(MemoryImage theImage) {
        float[] anHsb = new float[3];
        int aCount = 0;
        for (int aPixel : theImage.getBits()) {
            if ((aPixel >>> 24) < 128) {
                continue;
            }
            java.awt.Color.RGBtoHSB((aPixel >> 16) & 0xFF, (aPixel >> 8) & 0xFF, aPixel & 0xFF, anHsb);
            if (anHsb[1] > 0.3f && (anHsb[0] < 0.08f || anHsb[0] > 0.94f)) {
                aCount++;
            }
        }
        return aCount;
    }

    private static int countOpaque(MemoryImage theImage) {
        int aCount = 0;
        for (int aPixel : theImage.getBits()) {
            if ((aPixel >>> 24) != 0) {
                aCount++;
            }
        }
        return aCount;
    }

    /** Writes a {@code MemoryImage}'s 0xAARRGGBB pixels out as a PNG. */
    private static void writePng(Path theFile, MemoryImage theImage) throws Exception {
        java.awt.image.BufferedImage aImage = new java.awt.image.BufferedImage(
                theImage.mWidth, theImage.mHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        aImage.setRGB(0, 0, theImage.mWidth, theImage.mHeight, theImage.getBits(), 0, theImage.mWidth);
        javax.imageio.ImageIO.write(aImage, "png", theFile.toFile());
    }

    private static void report(String theKey, Object theValue) {
        REPORT.append(theKey).append('=').append(theValue).append('\n');
    }

    private static void flush(Path theReportPath) throws Exception {
        Files.writeString(theReportPath, REPORT.toString());
    }
}
