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
import cn.enaium.fabricpvz.contentmod.ContentModAssets;
import cn.enaium.fabricpvz.portable.lawn.Board;
import cn.enaium.fabricpvz.portable.lawn.GameMode;
import cn.enaium.fabricpvz.portable.lawn.GameScenes;
import cn.enaium.fabricpvz.portable.lawn.LawnApp;
import cn.enaium.fabricpvz.portable.lawn.Plant;
import cn.enaium.fabricpvz.portable.lawn.PlantWeapon;
import cn.enaium.fabricpvz.portable.lawn.PlantingReason;
import cn.enaium.fabricpvz.portable.lawn.ReanimationID;
import cn.enaium.fabricpvz.portable.lawn.Resources;
import cn.enaium.fabricpvz.portable.lawn.SeedType;
import cn.enaium.fabricpvz.portable.lawn.Zombie;
import cn.enaium.fabricpvz.portable.lawn.content.PlantTypeRegistry;
import cn.enaium.fabricpvz.portable.lawn.content.ZombieTypeRegistry;
import cn.enaium.fabricpvz.portable.sexy.Common;
import cn.enaium.fabricpvz.portable.sexy.Rect;
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
 *   <li>the mod's registrations, as the game's own definition tables see them;</li>
 *   <li>the mod's art: the packet images loaded from the mod jar, bound into the game's resource
 *       table, and re-installed by the mod's Mixin after a resource group load;</li>
 *   <li>a real headless board: both plants placed and behaving, the zombie spawning, walking and
 *       dying, and the game still in {@code SCENE_PLAYING}.</li>
 * </ol>
 */
public final class ContentModGameMain {

    /** Walk speed the harness gives the zombie; the retail reanimation's ground track provides it normally. */
    private static final float WALK_SPEED = 0.5f;

    /** Ticks the zombie walks in the frame-loop check. */
    private static final int WALK_TICKS = 30;

    /** Ticks the harness gives the dying zombie to be marked dead by the game. */
    private static final int DEATH_TICK_LIMIT = 300;

    private static final StringBuilder REPORT = new StringBuilder();
    private static String sError = "";

    /** The headless app every phase after the loader phase runs against. */
    private static LawnApp sApp;

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

            boardPhase();
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

        report("apiInitialized", PvzFabricApi.isInitialized());
        report("apiVersion", PvzFabricApi.getVersion());
        report("apiSeesMod", PvzFabricApi.getModContainer(ContentMod.MOD_ID).isPresent());
        report("contentModEntrypointRan", ContentMod.sniperPea != null && ContentMod.sunOrchid != null
                && ContentMod.bulwarkZombie != null);
    }

    // ---------------------------------------------------------------- phase 2

    /** The registered content, as the game's own tables read it. */
    private static void registrationPhase() {
        report("plantsRegistered", PlantRegistry.count());
        report("zombiesRegistered", ZombieRegistry.count());
        report("sniperPeaRegistered", PlantRegistry.get("sniper_pea").isPresent());
        report("sunOrchidRegistered", PlantRegistry.get("sun_orchid").isPresent());
        report("bulwarkZombieRegistered", ZombieRegistry.get("bulwark_zombie").isPresent());

        int aPeaSeed = ContentMod.sniperPea.seedType();
        int aOrchidSeed = ContentMod.sunOrchid.seedType();
        int aZombieType = ContentMod.bulwarkZombie.zombieType();
        report("sniperPeaSeedType", aPeaSeed);
        report("sunOrchidSeedType", aOrchidSeed);
        report("bulwarkZombieType", aZombieType);
        report("seedTypesOutsideUpstream", aPeaSeed >= SeedType.CUSTOM_SEED_FIRST
                && aOrchidSeed >= SeedType.CUSTOM_SEED_FIRST);
        report("zombieTypeOutsideUpstream", aZombieType >= cn.enaium.fabricpvz.portable.lawn.ZombieType.CUSTOM_ZOMBIE_FIRST);

        // The game's per-seed definition rows, i.e. what the seed packet, the almanac and the
        // planting rules read.
        report("sniperPeaDefinitionCost", Plant.getPlantDefinition(aPeaSeed).mSeedCost);
        report("sniperPeaDefinitionRefresh", Plant.getPlantDefinition(aPeaSeed).mRefreshTime);
        report("sniperPeaDefinitionLaunchRate", Plant.getPlantDefinition(aPeaSeed).mLaunchRate);
        report("sniperPeaDefinitionReanim", Plant.getPlantDefinition(aPeaSeed).mReanimationType);
        report("sniperPeaDefinitionSubclass", Plant.getPlantDefinition(aPeaSeed).mSubClass);
        report("sunOrchidDefinitionCost", Plant.getPlantDefinition(aOrchidSeed).mSeedCost);
        report("sunOrchidDefinitionRefresh", Plant.getPlantDefinition(aOrchidSeed).mRefreshTime);
        report("sunOrchidDefinitionLaunchRate", Plant.getPlantDefinition(aOrchidSeed).mLaunchRate);
        report("sunOrchidDefinitionReanim", Plant.getPlantDefinition(aOrchidSeed).mReanimationType);

        report("sniperPeaName", Plant.getNameString(aPeaSeed, SeedType.SEED_NONE));
        report("sniperPeaTooltip", Plant.getToolTip(aPeaSeed));
        report("sniperPeaAlmanac", PlantTypeRegistry.almanacDescription(aPeaSeed));
        report("sunOrchidName", Plant.getNameString(aOrchidSeed, SeedType.SEED_NONE));
        report("sunOrchidTooltip", Plant.getToolTip(aOrchidSeed));
        report("sunOrchidAlmanac", PlantTypeRegistry.almanacDescription(aOrchidSeed));

        report("bulwarkName", ZombieTypeRegistry.displayName(aZombieType));
        report("bulwarkAlmanac", ZombieTypeRegistry.almanacDescription(aZombieType));
        report("bulwarkValue", Zombie.getZombieDefinition(aZombieType).mZombieValue);
        report("bulwarkStartingLevel", Zombie.getZombieDefinition(aZombieType).mStartingLevel);
        report("bulwarkFirstAllowedWave", Zombie.getZombieDefinition(aZombieType).mFirstAllowedWave);
        report("bulwarkPickWeight", Zombie.getZombieDefinition(aZombieType).mPickWeight);

        // Upstream content is untouched: the peashooter's own row still reads what it always did.
        report("peashooterDefinitionCost", Plant.getPlantDefinition(SeedType.SEED_PEASHOOTER).mSeedCost);
        report("peashooterDefinitionLaunchRate", Plant.getPlantDefinition(SeedType.SEED_PEASHOOTER).mLaunchRate);
        report("normalZombieAllowed", Zombie.getZombieDefinition(cn.enaium.fabricpvz.portable.lawn.ZombieType.ZOMBIE_NORMAL).mZombieValue);
    }

    // ---------------------------------------------------------------- phase 3

    /**
     * The mod's own art: the two packet images must be loaded from the mod jar, sit in the game's
     * resource table and survive a resource group load (which is what the mod's Mixin is for).
     */
    private static void assetPhase() {
        // A real headless app first: the mod's images must be created with the app the game is
        // running, exactly like the real INITIALIZED dispatch does it.
        sApp = new HeadlessLawn.LoadedReanimApp();
        sApp.mGameMode = GameMode.GAMEMODE_ADVENTURE;
        sApp.mGameScene = GameScenes.SCENE_PLAYING;
        HeadlessLawn.initialize(sApp);

        Path aAssets = Path.of(System.getProperty("pvz.test.resources", ""));
        boolean aAssetsPresent = !aAssets.toString().isBlank() && Files.isDirectory(aAssets);
        report("assetsPresent", aAssetsPresent);

        // Real-asset check: the two ids the mod uses must be real retail resources, and the retail
        // archive must actually contain the files they map to (properties/resources.xml maps
        // IMAGE_PEA_SPLATS to particles/pea_splats and IMAGE_POTATOMINE_PARTICLES to
        // particles/potatomine_particles, both in the LoadingImages group).
        if (aAssetsPresent) {
            try {
                cn.enaium.fabricpvz.portable.sexy.pak.PakInterface aPak =
                        cn.enaium.fabricpvz.portable.sexy.pak.PakInterface.getInstance();
                aPak.setResourceFolder(aAssets.toString());
                aPak.addPakFile(aAssets.resolve("main.pak"));
                report("retailPeaSplatsInPak", aPak.exists("particles/pea_splats.png"));
                report("retailPotatomineParticlesInPak", aPak.exists("particles/potatomine_particles.png"));
            } catch (Throwable aThrowable) {
                report("retailPakError", aThrowable.toString());
            }
        }

        // The game fires this at the end of SexyAppBase::Init, through the API's AppHooksBridge.
        GameLifecycleEvents.INITIALIZED.invoker().onInitialize();
        report("packetArtInstalls", ContentModAssets.packetArtInstalls);
        report("assetLoadError", ContentModAssets.loadError.isEmpty() ? "none" : ContentModAssets.loadError);
        report("packetArtBound", ContentModAssets.packetArtBound());

        // The retail resource the two ids belong to: in range, an image, and unread by this port.
        report("sniperPeaPacketSlot", ContentModAssets.PACKET_IMAGE_SNIPER_PEA);
        report("sniperPeaPacketSlotName", Resources.getName(ContentModAssets.PACKET_IMAGE_SNIPER_PEA));
        report("sniperPeaPacketSlotGroup", Resources.getGroup(ContentModAssets.PACKET_IMAGE_SNIPER_PEA));
        report("sniperPeaPacketSlotIsImage", Resources.isImage(ContentModAssets.PACKET_IMAGE_SNIPER_PEA));
        report("sunOrchidPacketSlot", ContentModAssets.PACKET_IMAGE_SUN_ORCHID);
        report("sunOrchidPacketSlotName", Resources.getName(ContentModAssets.PACKET_IMAGE_SUN_ORCHID));
        report("sunOrchidPacketSlotGroup", Resources.getGroup(ContentModAssets.PACKET_IMAGE_SUN_ORCHID));
        report("sunOrchidPacketSlotIsImage", Resources.isImage(ContentModAssets.PACKET_IMAGE_SUN_ORCHID));

        // The bound images are the mod's PNGs, not the headless stubs: 72x70 with real colours.
        report("sniperPeaPacketSize", sizeOf(Resources.getImageById(ContentModAssets.PACKET_IMAGE_SNIPER_PEA)));
        report("sunOrchidPacketSize", sizeOf(Resources.getImageById(ContentModAssets.PACKET_IMAGE_SUN_ORCHID)));
        report("sniperPeaPacketCentrePixel",
                pixelAt(Resources.getImageById(ContentModAssets.PACKET_IMAGE_SNIPER_PEA), 36, 35));
        report("sunOrchidPacketCentrePixel",
                pixelAt(Resources.getImageById(ContentModAssets.PACKET_IMAGE_SUN_ORCHID), 36, 35));

        // The zombie torso: the retail torso image, recoloured, loadable from the jar.
        Image aTorso = ContentModAssets.zombieTorsoImage();
        report("zombieTorsoSize", sizeOf(aTorso));

        // Simulate what the game's own resource group load does to these two ids: the LoadingImages
        // group binds its entries by overwriting them, so the mod's art is wiped from the slots. The
        // mod's Mixin and tick hook have to put it back; boardPhase proves both.
        Resources.setImageById(ContentModAssets.PACKET_IMAGE_SNIPER_PEA, null);
        Resources.setImageById(ContentModAssets.PACKET_IMAGE_SUN_ORCHID, null);
        report("packetArtBoundAfterClobber", ContentModAssets.packetArtBound());
        report("mixinCallbacksBefore", ContentModAssets.mixinCallbacks);
        report("reapplyCallsBefore", ContentModAssets.reapplyCalls);
    }

    // ---------------------------------------------------------------- phase 4

    /** A real headless board: both plants placed and behaving, the zombie spawning, walking and dying. */
    private static void boardPhase() {
        LawnApp aApp = sApp;
        aApp.mPlayerInfo.mLevel = 1;
        // Not a first-time player, so a produced sun does not trigger the tutorial advice popups.
        aApp.mPlayerInfo.mFinishedAdventure = 1;
        Common.sRand(0x5EED);

        // The headless fixture has just bound a 1x1 stub for every image id, which is exactly what a
        // resource group load does to the ids it owns. Now load a real group through the game's own
        // extractor: whatever it does to the two packet slots, the mod's Mixin has to have restored
        // the mod's art by the time the call returns.
        int aMixinBefore = ContentModAssets.mixinCallbacks;
        int aReapplyBefore = ContentModAssets.reapplyCalls;
        String aGroupError = "none";
        Boolean aGroupResult = null;
        try {
            aGroupResult = Resources.extractResourcesByName(aApp.mResourceManager, "DelayLoad_AwardScreen");
        } catch (Throwable aThrowable) {
            aGroupError = aThrowable.toString();
        }
        report("resourceGroupLoadResult", aGroupResult);
        report("resourceGroupLoadError", aGroupError);
        report("mixinCallbacks", ContentModAssets.mixinCallbacks - aMixinBefore);
        report("reapplyCalls", ContentModAssets.reapplyCalls - aReapplyBefore);
        report("packetArtBoundAfterGroupLoad", ContentModAssets.packetArtBound());

        Board aBoard = new Board(aApp);
        aBoard.initLevel();
        report("boardLevel", aBoard.mLevel);
        report("boardWaves", aBoard.mNumWaves);

        int aPeaSeed = ContentMod.sniperPea.seedType();
        int aOrchidSeed = ContentMod.sunOrchid.seedType();
        int aZombieType = ContentMod.bulwarkZombie.zombieType();
        report("sniperPeaSeedAvailable", aApp.hasSeedType(aPeaSeed));
        report("sunOrchidSeedAvailable", aApp.hasSeedType(aOrchidSeed));

        // The planting rule the mod registered is consulted by Board::CanPlantAt for its own seed only.
        report("plantingRuleAllowsColumn7", aBoard.canPlantAt(7, 2, aPeaSeed) == PlantingReason.PLANTING_OK);
        report("plantingRuleVetoesEntryColumn", aBoard.canPlantAt(8, 2, aPeaSeed) == PlantingReason.PLANTING_NOT_HERE);
        report("plantingRuleLeavesUpstreamAlone",
                aBoard.canPlantAt(8, 2, SeedType.SEED_PEASHOOTER) == PlantingReason.PLANTING_OK);

        // Plant 1: the Sniper Pea, with its registered stats and its whole-lane attack rectangle.
        Plant aPea = aBoard.addPlant(1, 2, aPeaSeed, SeedType.SEED_NONE);
        report("sniperPeaPlaced", aPea != null);
        report("sniperPeaSeedTypeMatches", aPea.mSeedType == aPeaSeed);
        report("sniperPeaHealth", aPea.mPlantHealth);
        report("sniperPeaMaxHealth", aPea.mPlantMaxHealth);
        report("sniperPeaSubclass", aPea.mSubclass);
        report("sniperPeaLaunchRate", aPea.mLaunchRate);
        report("sniperPeaBodyReanimLoaded", aPea.mBodyReanimID != ReanimationID.REANIMATIONID_NULL);
        report("sniperPeaAttackRect", rectOf(aPea.getPlantAttackRect(PlantWeapon.WEAPON_PRIMARY)));
        report("upstreamPeashooterAttackRect", rectOf(aBoard.addPlant(0, 4, SeedType.SEED_PEASHOOTER, SeedType.SEED_NONE)
                .getPlantAttackRect(PlantWeapon.WEAPON_PRIMARY)));

        // Plant 2: the Sun Orchid, with its wider collision rectangle and its own sun cadence.
        Plant aOrchid = aBoard.addPlant(3, 2, aOrchidSeed, SeedType.SEED_NONE);
        report("sunOrchidPlaced", aOrchid != null);
        report("sunOrchidHealth", aOrchid.mPlantHealth);
        report("sunOrchidMaxHealth", aOrchid.mPlantMaxHealth);
        report("sunOrchidLaunchRate", aOrchid.mLaunchRate);
        report("sunOrchidRectWidth", aOrchid.getPlantRect().mWidth);
        report("sunOrchidOwnWidth", aOrchid.mWidth);

        int aCoinsBefore = aBoard.mCoins.mSize;
        int aSunsBefore = ContentMod.sunsProduced;
        aOrchid.mLaunchCounter = 1;
        aOrchid.update();
        report("sunOrchidSunsProduced", ContentMod.sunsProduced - aSunsBefore);
        report("sunOrchidCoinsAdded", aBoard.mCoins.mSize - aCoinsBefore);

        int aTicksBefore = ContentMod.orchidTickCallbacks;
        aPea.update();
        aOrchid.update();
        report("orchidTickCallbacks", ContentMod.orchidTickCallbacks - aTicksBefore);
        report("boardPlantCount", plantCount(aBoard));

        // The custom zombie through the board's own spawner.
        int aSpawnsBefore = ContentMod.zombieTorsosRecoloured;
        Zombie aZombie = aBoard.addZombieInRow(aZombieType, 3, 0);
        report("zombieSpawned", aZombie != null);
        report("zombieTypeMatches", aZombie.mZombieType == aZombieType);
        report("zombieBodyHealth", aZombie.mBodyHealth);
        report("zombieBodyMaxHealth", aZombie.mBodyMaxHealth);
        report("zombieHelmType", aZombie.mHelmType);
        report("zombieHelmHealth", aZombie.mHelmHealth);
        report("zombieScale", aZombie.mScaleZombie);
        report("zombieAttackRect", rectOf(aZombie.mZombieAttackRect));
        report("plainZombieAttackRect", rectOf(new Zombie().mZombieAttackRect));
        report("zombieBodyReanimLoaded", aZombie.mBodyReanimID != ReanimationID.REANIMATIONID_NULL);
        report("zombieTorsosRecoloured", ContentMod.zombieTorsosRecoloured - aSpawnsBefore);
        report("zombieAllowedOnLevel", aBoard.mZombieAllowed[aZombieType]);
        report("wavesContainingModZombie", wavesContaining(aBoard, aZombieType));
        report("upstreamZombieStillAllowed",
                aBoard.mZombieAllowed[cn.enaium.fabricpvz.portable.lawn.ZombieType.ZOMBIE_NORMAL]);

        // Walking. The headless run has no retail .reanim data, so the ground-track velocity the
        // retail animation carries is supplied here on every tick, exactly as the game's own headless
        // suites do.
        float aStartX = aZombie.mPosX;
        int aZombieTicksBefore = ContentMod.zombieTickCallbacks;
        for (int aTick = 0; aTick < WALK_TICKS; aTick++) {
            aZombie.mVelX = WALK_SPEED;
            aZombie.update();
        }
        report("zombieWalkedDistance", aStartX - aZombie.mPosX);
        report("zombieTickCallbacks", ContentMod.zombieTickCallbacks - aZombieTicksBefore);

        // The tick hook bolts the pail back on every BULWARK_HELM_REPAIR_TICKS ticks.
        int aRepairsBefore = ContentMod.helmRepairs;
        aZombie.mHelmHealth = 100;
        aZombie.mZombieAge = ContentMod.BULWARK_HELM_REPAIR_TICKS - 1;
        aZombie.update();
        report("zombieHelmRepairs", ContentMod.helmRepairs - aRepairsBefore);
        report("zombieHelmHealthAfterTick", aZombie.mHelmHealth);

        // Death: a lethal hit has to kill the registered zombie like any other. Depending on whether
        // there is a retail death animation to play the game ends the zombie immediately or runs its
        // dying state first, so the harness ticks until the game itself marks it dead.
        aZombie.takeDamage(100000, 0);
        report("zombieBodyHealthAfterLethalHit", aZombie.mBodyHealth);
        report("zombieDying", aZombie.isDeadOrDying());
        report("zombiePhaseAfterLethalHit", aZombie.mZombiePhase);

        int aDeathTicks = -1;
        for (int aTick = 0; aTick < DEATH_TICK_LIMIT && !aZombie.mDead; aTick++) {
            aZombie.update();
            aDeathTicks = aTick + 1;
        }
        report("zombieDeathTicks", aZombie.mDead ? aDeathTicks : -1);
        report("zombieDead", aZombie.mDead);

        // The per-tick safety net the mod registered through LawnEvents: clearing a slot and asking
        // the API's hook chain for one tick must put the mod's art back.
        Resources.setImageById(ContentModAssets.PACKET_IMAGE_SNIPER_PEA, null);
        LawnEvents.TICK.invoker().onTick(aBoard, 1);
        report("packetArtBoundAfterTickHook", ContentModAssets.packetArtBound());

        report("scene", aApp.mGameScene == GameScenes.SCENE_PLAYING ? "PLAYING" : String.valueOf(aApp.mGameScene));
    }

    // ---------------------------------------------------------------- helpers

    private static int plantCount(Board theBoard) {
        int aCount = 0;
        for (Plant aPlant : theBoard.mPlants) {
            if (!aPlant.mDead) {
                aCount++;
            }
        }
        return aCount;
    }

    private static int wavesContaining(Board theBoard, int theZombieType) {
        int aCount = 0;
        for (int aWave = 0; aWave < theBoard.mNumWaves; aWave++) {
            for (int aIndex = 0; aIndex < Board.MAX_ZOMBIES_IN_WAVE; aIndex++) {
                int aZombieType = theBoard.mZombiesInWave[aWave][aIndex];
                if (aZombieType == cn.enaium.fabricpvz.portable.lawn.ZombieType.ZOMBIE_INVALID) {
                    break;
                }
                if (aZombieType == theZombieType) {
                    aCount++;
                    break;
                }
            }
        }
        return aCount;
    }

    private static String rectOf(Rect theRect) {
        return theRect == null ? "null"
                : theRect.mX + "," + theRect.mY + "," + theRect.mWidth + "," + theRect.mHeight;
    }

    private static String sizeOf(Image theImage) {
        return theImage == null ? "missing" : theImage.mWidth + "x" + theImage.mHeight;
    }

    private static String pixelAt(Image theImage, int theX, int theY) {
        if (!(theImage instanceof MemoryImage aMemory) || theX >= aMemory.mWidth || theY >= aMemory.mHeight) {
            return "missing";
        }

        int[] aBits = aMemory.getBits();
        return String.format("0x%08X", aBits[theY * aMemory.mWidth + theX]);
    }

    private static String requiredProperty(String theName) {
        String aValue = System.getProperty(theName, "");
        if (aValue.isBlank()) {
            throw new IllegalStateException("-D" + theName + " is required");
        }
        return aValue;
    }

    private static void report(String theKey, Object theValue) {
        REPORT.append(theKey).append('=').append(theValue).append('\n');
    }

    private static void flush(Path theReportPath) throws Exception {
        Files.writeString(theReportPath, REPORT.toString());
    }
}
