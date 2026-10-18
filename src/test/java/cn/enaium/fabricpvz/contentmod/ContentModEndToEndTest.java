package cn.enaium.fabricpvz.contentmod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import cn.enaium.fabricpvz.portable.lawn.PlantSubClass;
import cn.enaium.fabricpvz.portable.lawn.ProjectileType;
import cn.enaium.fabricpvz.portable.lawn.ReanimationType;
import cn.enaium.fabricpvz.portable.lawn.SeedType;

/**
 * End-to-end verification of the content mod on the real Fabric Loader.
 *
 * <p>The test does exactly what a launcher does: it copies the <em>built mod jar</em> into a real
 * {@code mods/} directory and starts {@code net.fabricmc.loader.impl.launch.knot.KnotClient} in a
 * second JVM with the game, the loader (plus Mixin and ASM, pulled in by
 * the game jar carries) and the API modules on the class path. The loader
 * finds the game through the provider's {@code GameProvider}, resolves the mod, runs its
 * {@code ModInitializer}, applies the API modules' Mixin configurations and only then calls the game entry point
 * - which this test redirects to {@link cn.enaium.fabricpvz.contentmod.test.ContentModGameMain} with
 * {@code -Dpvz.game.mainClass}.
 *
 * <p>The harness reports through a file because the loader defines its own copies of the game, the
 * API and the mod classes; the assertions below are all made on that report.
 */
class ContentModEndToEndTest {

    /** Launcher main class of the real Fabric Loader (client environment), from its own manifest. */
    private static final String LAUNCHER_CLASS = "net.fabricmc.loader.impl.launch.knot.KnotClient";

    /** System property carrying the class path the launcher has to run with. */
    private static final String FORK_CLASSPATH_PROPERTY = "pvz.test.forkClasspath";

    /** System property naming the built mod jar. */
    private static final String MOD_JAR_PROPERTY = "pvz.test.modJar";

    /** Mod ids that must be resolved next to this mod, i.e. the game and the API modules. */
    private static final List<String> DEPENDENCY_MODS = List.of(
            "fabricloader", "pvz-portable",
            "fabric-api", "api-base", "api-events", "api-plant", "api-resource", "api-zombie");

    @TempDir
    Path tmp;

    @Test
    void bootsTheLoaderWithTheModJarAndRunsTheContentInARealBoard() throws Exception {
        Path modJar = Path.of(requiredProperty(MOD_JAR_PROPERTY));
        assertTrue(Files.isRegularFile(modJar), "the mod jar must be built first: " + modJar);
        assertModJarContents(modJar);

        Path mods = Files.createDirectories(tmp.resolve("mods"));
        Path installed = Files.copy(modJar, mods.resolve(modJar.getFileName()));
        assertTrue(Files.size(installed) > 0, "the installed mod jar must not be empty");

        Path runDir = Files.createDirectories(tmp.resolve("run"));
        Path report = tmp.resolve("content-report.txt");
        Path output = tmp.resolve("loader-output.txt");

        int exitCode = launch(mods, runDir, report, output);
        assertEquals(0, exitCode, "Fabric Loader must boot the game with the mod: " + failureMessage(output, report));

        // Keep the packet dump the harness wrote next to its report: it is the only artifact that
        // shows what a seed packet of this mod actually looks like (build/packet-dump/*.png).
        Path aDump = report.getParent().resolve("packet-dump");
        if (Files.isDirectory(aDump)) {
            Path aTarget = Path.of("build", "packet-dump");
            try (var aFiles = Files.walk(aDump)) {
                for (Path aFile : aFiles.filter(Files::isRegularFile).toList()) {
                    Files.createDirectories(aTarget);
                    Files.copy(aFile, aTarget.resolve(aFile.getFileName()),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        Map<String, String> values = readReport(report);

        // 1) The loader resolved the game, the API modules and this mod jar.
        //    The workspace releases every artifact under one version, so these two assert what the
        //    provider and the API report against what the loader resolved, not a hard-coded number.
        assertEquals(values.get("gameVersionByLoader"), values.get("gameVersion"),
                "the provider must report the version of the game it resolved");
        assertEquals("client", values.get("environment"));
        assertEquals("true", values.get("contentModLoaded"));
        for (String id : DEPENDENCY_MODS) {
            assertTrue(List.of(values.get("modsLoaded").split(",")).contains(id),
                    "mod " + id + " must be loaded, got " + values.get("modsLoaded"));
        }
        assertEquals("true", values.get("contentModEntrypointRan"), "the mod's ModInitializer must have run");
        assertEquals("true", values.get("apiInitialized"), "the API entry point must run before the game");
        assertEquals(values.get("apiVersionByLoader"), values.get("apiVersion"),
                "the API must report the version the loader resolved");

        // 2) The three plants are registered through the public API and the game's own definition table
        //    reports the registered stats.
        assertEquals("3", values.get("plantsRegistered"));
        assertEquals("0", values.get("zombiesRegistered"), "this mod registers no zombie any more");
        assertEquals("true", values.get("redPeashooterRegistered"));
        assertEquals("true", values.get("seedTypesOutsideUpstream"));
        assertEquals(String.valueOf(SeedType.SEED_SNOWPEA), values.get("redPeashooterUnlockSeed"),
                "the plant must be gated behind the Snow Pea's award");

        assertEquals("175", values.get("redPeashooterDefinitionCost"), "the registered sun cost");
        assertEquals("750", values.get("redPeashooterDefinitionRefresh"));
        assertEquals("150", values.get("redPeashooterDefinitionLaunchRate"), "the peashooter's cadence");
        assertEquals(String.valueOf(ReanimationType.REANIM_PEASHOOTER), values.get("redPeashooterDefinitionReanim"));
        assertEquals(String.valueOf(PlantSubClass.SUBCLASS_SHOOTER), values.get("redPeashooterDefinitionSubclass"));
        assertEquals("Red Peashooter", values.get("redPeashooterName"));
        assertEquals("Fires burning peas instead of plain ones.", values.get("redPeashooterTooltip"));

        // The two plants added next to it: the repeater's and the gatling pea's own stats with the
        // mod's costs, and their burst.
        assertEquals("true", values.get("redRepeaterRegistered"));
        assertEquals(String.valueOf(SeedType.SEED_REPEATER), values.get("redRepeaterUnlockSeed"),
                "the repeater twin is gated behind the adventure's own Repeater");
        assertEquals("225", values.get("redRepeaterDefinitionCost"));
        assertEquals("750", values.get("redRepeaterDefinitionRefresh"), "the repeater's own recharge");
        assertEquals("150", values.get("redRepeaterDefinitionLaunchRate"));
        assertEquals(String.valueOf(ReanimationType.REANIM_REPEATER), values.get("redRepeaterDefinitionReanim"));
        assertEquals("2", values.get("redRepeaterBurst"));
        assertEquals("Red Repeater", values.get("redRepeaterName"));

        assertEquals("true", values.get("redGatlingRegistered"));
        assertEquals(String.valueOf(SeedType.SEED_GATLINGPEA), values.get("redGatlingUnlockSeed"),
                "the gatling twin is gated behind the shop's Gatling Pea");
        assertEquals("275", values.get("redGatlingDefinitionCost"));
        assertEquals("5000", values.get("redGatlingDefinitionRefresh"), "the gatling pea's own recharge");
        assertEquals("150", values.get("redGatlingDefinitionLaunchRate"));
        assertEquals(String.valueOf(ReanimationType.REANIM_GATLINGPEA), values.get("redGatlingDefinitionReanim"));
        assertEquals("4", values.get("redGatlingBurst"));
        assertEquals("Red Gatling Pea", values.get("redGatlingName"));

        // Upstream content is untouched.
        assertEquals("100", values.get("peashooterDefinitionCost"));
        assertEquals("150", values.get("peashooterDefinitionLaunchRate"));
        assertEquals("175", values.get("snowPeaDefinitionCost"));
        assertEquals("200", values.get("repeaterDefinitionCost"));
        assertEquals("750", values.get("repeaterDefinitionRefresh"));
        assertEquals("250", values.get("gatlingDefinitionCost"));
        assertEquals("5000", values.get("gatlingDefinitionRefresh"));

        // 3) The mod's art, drawn at the seed chooser's moment: the icon is the retail twin's packet
        //    art re-mapped to red, it reaches the game through the plants' art supplier, and the game
        //    really draws it - with no board and no tick behind it (../verification/VERIFICATION.md §53).
        assertEquals("none", values.get("assetLoadError"));

        // The purple (upgrade) packet is the game's own reaction to Plant::IsUpgrade, which the API's
        // PlantMixin routes to the registration: the gatling twin declares it, the peashooter twin does
        // not, and the shop plant the gatling twin copies is an upgrade too.
        assertEquals("true", values.get("plantIsUpgradeRedGatling"),
                "the red gatling pea must use the upgrade (purple) packet");
        assertEquals("false", values.get("plantIsUpgradeRedRepeater"),
                "the red repeater is not an upgrade plant");
        assertEquals("false", values.get("plantIsUpgradeRedPeashooter"));
        assertEquals("true", values.get("plantIsUpgradeRetailGatling"),
                "the retail gatling pea is the upgrade plant the red twin copies");

        boolean assetsPresent = Boolean.parseBoolean(values.get("assetsPresent"));
        if (assetsPresent) {
            assertEquals("true", values.get("retailPakReadable"),
                    "the retail archive the packets are drawn from must be readable");

            // The drawn packets: the mod's three come out of the art supplier, so they show the red
            // plant where the retail twins' own packets - and the packet background - have no red at
            // all, and the red pixels they show are the ones of the icon the supplier handed over
            // (nothing else in a packet is red). This is what the bug of §53 fails: with the icon
            // built by a mechanism that needs a tick, or not handed to the game at all, the packets
            // carry no red of the mod's making.
            for (String aTwin : List.of("peashooter", "repeater", "gatling")) {
                assertEquals("0", values.get("retail_" + aTwin + "PacketRedPixels"),
                        "a retail peashooter-family packet is green, got "
                                + values.get("retail_" + aTwin + "PacketRedPixels"));
                int aIconRed = Integer.parseInt(values.get("red_" + aTwin + "IconRedPixels"));
                int aPacketRed = Integer.parseInt(values.get("red_" + aTwin + "PacketRedPixels"));
                assertTrue(aIconRed > 200,
                        "the red " + aTwin + "'s art supplier must hand the game a red icon, got "
                                + aIconRed);
                // The packet is that icon drawn 1:1 over the packet background; only the blended rim
                // of the icon can fall outside both counts (the background is not red).
                assertTrue(Math.abs(aIconRed - aPacketRed) <= aPacketRed / 50 + 4,
                        "the red " + aTwin + "'s packet must be drawn from that icon, got " + aPacketRed
                                + " red pixels against the icon's " + aIconRed);
                assertPacketOpaqueNear(values, "red_" + aTwin, "retail_" + aTwin);
            }

            // The icon itself: the retail peashooter's packet art with its colours re-mapped - same
            // pixels painted, different colours. A scattered or hand-rendered icon fails the mask
            // agreement; a copy of the retail art that was not recoloured fails the changed-pixel count.
            assertEquals("50x70", values.get("packetSize"), "the packet's own cel size");
            assertEquals("0xFF", values.get("packetPlantPixel").substring(0, 4),
                    "the icon must hold real, opaque pixels where the plant is, got "
                            + values.get("packetPlantPixel"));
            assertEquals(values.get("retailOpaquePixels"), values.get("packetOpaquePixels"),
                    "the icon must paint exactly the pixels the retail packet art paints");
            assertEquals("100", values.get("packetMaskAgreementPercent"),
                    "the icon must be the retail peashooter's packet art, pixel for pixel");
            assertTrue(Integer.parseInt(values.get("packetRecolouredPixels")) > 200,
                    "the icon must be recoloured, got " + values.get("packetRecolouredPixels"));

            // A resource group load (or its attempt - the report carries whether the group resolved)
            // and a board tick take nothing away: the supplier is asked per draw, so the packet is red
            // again every time.
            assertTrue(Integer.parseInt(values.get("packetRedPixelsAfterGroupLoad")) > 200,
                    "the packet must still show the icon after a resource group load, got "
                            + values.get("packetRedPixelsAfterGroupLoad"));
            assertTrue(Integer.parseInt(values.get("packetRedPixelsAfterTickHook")) > 200,
                    "the packet must still show the icon after a board tick, got "
                            + values.get("packetRedPixelsAfterTickHook"));
        } else {
            System.out.println("[content-mod-e2e] no retail assets on this machine: the packet-pixel "
                    + "checks were skipped, the registration and board checks still ran");
        }

        // 4) A real board: the unlock gate, the chooser's second page, and the plant itself.
        assertEquals("1", values.get("boardLevel"));
        assertEquals("false", values.get("snowPeaAvailableBefore"),
                "a first-time player has no Snow Pea yet");
        assertEquals("true", values.get("redPeashooterLockedBefore"),
                "the seed must be out of reach while the Snow Pea is");
        assertEquals("true", values.get("snowPeaAvailableAfter"));
        assertEquals("true", values.get("redPeashooterUnlockedAfter"),
                "the Snow Pea's award must unlock the registered seed");

        assertEquals("2", values.get("chooserPages"), "the retail page plus the registered seed's page");
        assertEquals("0", values.get("chooserPage"), "the chooser opens on the retail page");
        assertEquals("false", values.get("chooserFirstPageShowsRedPeashooter"));
        assertEquals("SEED_IN_CHOOSER", values.get("chooserSeedState"));
        assertEquals("1", values.get("chooserPageAfterSwitch"));
        assertEquals("true", values.get("chooserSecondPageSlot0IsRedPeashooter"),
                "the seed must be the first square of the second page");
        assertEquals("true", values.get("chooserSecondPageSeedSquare"));

        // 4b) The almanac: the plant page is the retail grid plus one page for the registered seed,
        //     whose card shows the mod's name and description.
        assertEquals("2", values.get("almanacPlantPages"), "the retail page plus the registered seed's page");
        assertEquals("0", values.get("almanacPlantPage"), "the almanac opens on the retail page");
        assertEquals("false", values.get("almanacFirstPageSlot0IsRedPeashooter"),
                "the peashooter keeps the retail grid's first square");
        assertEquals("1", values.get("almanacPlantPageAfterSwitch"));
        assertEquals("true", values.get("almanacSecondPageSlot0IsRedPeashooter"),
                "the seed must be the first square of the page behind the retail grid");
        assertEquals("true", values.get("almanacSelectedSeedIsRedPeashooter"),
                "clicking that square must select it");
        assertEquals("Red Peashooter", values.get("almanacSelectedPlantName"),
                "the card's name comes from the mod, through Plant::GetNameString");
        assertEquals(ContentMod.RED_PEASHOOTER_ALMANAC_DESCRIPTION, values.get("almanacSelectedPlantDescription"),
                "the card's description comes from the mod, through the API's AlmanacMixin");
        assertEquals("true", values.get("almanacPortraitPlaced"),
                "the card draws a real plant for the selected seed");
        assertEquals("true", values.get("almanacPortraitHeadReanim"),
                "the portrait plant needs the head reanimation the game only creates for its own pea shooters");

        assertEquals("true", values.get("redPeashooterPlaced"));
        assertEquals("true", values.get("redPeashooterSeedTypeMatches"));
        assertEquals("300", values.get("redPeashooterHealth"), "the upstream default, not overridden");
        assertEquals("300", values.get("redPeashooterMaxHealth"));
        assertEquals(String.valueOf(PlantSubClass.SUBCLASS_SHOOTER), values.get("redPeashooterSubclass"));
        assertEquals("150", values.get("redPeashooterLaunchRate"));
        assertEquals("true", values.get("redPeashooterBodyReanimLoaded"));
        assertEquals("800", values.get("redPeashooterAttackRect").split(",")[2],
                "the upstream shooter rectangle, got " + values.get("redPeashooterAttackRect"));
        assertEquals("800", values.get("upstreamPeashooterAttackRect").split(",")[2],
                "the upstream peashooter keeps its own attack rectangle");

        if (assetsPresent) {
            assertEquals("1", values.get("redPeashooterPlants"), "the placed plant must be recoloured");
            assertEquals("26", values.get("redPeashooterRecolours"),
                    "the 13 art-carrying tracks of the plant's body and head reanimations must be recoloured");
            assertEquals("true", values.get("redPeashooterHeadReanim"),
                    "the mod must give the plant the head reanimation the game only gives its own seed types");
            assertTrue(Integer.parseInt(values.get("redPeashooterHeadAlpha")) > 0,
                    "the red head image must hold art, got " + values.get("redPeashooterHeadAlpha"));
            assertTrue(Integer.parseInt(values.get("redPeashooterBodyLeafAlpha")) > 0,
                    "the red leaf image must hold art, got " + values.get("redPeashooterBodyLeafAlpha"));
        } else {
            assertEquals("0", values.get("redPeashooterRecolours"),
                    "without retail .reanim data there is no track image to recolour");
            System.out.println("[content-mod-e2e] no retail assets on this machine: the red-material "
                    + "check was skipped");
        }

        // 5) The fire pea: the plant fires the projectile the mod registered.
        if (assetsPresent) {
            assertEquals("true", values.get("fireZombieSpawned"));
            assertEquals("true", values.get("fireTargetFound"),
                    "the plant must see the zombie the harness put in its lane");
            assertEquals("1", values.get("fireProjectileCount"));
            assertEquals(String.valueOf(ProjectileType.PROJECTILE_FIREBALL), values.get("fireProjectileTypes"),
                    "the registered plant must fire the fire pea");
            assertEquals("true", values.get("cachedPlantFrameSet"),
                    "the game's plant-frame cache must hold the mod's frame for the registered seed type");
            assertEquals("true", values.get("cachedPlantFrameIsRed"),
                    "the cursor preview draws the cached frame, so it must be the red one");

            assertEquals(String.valueOf(ReanimationType.REANIM_FIRE_PEA), values.get("fireProjectileBodies"),
                    "the fired pea must carry its flame body: a fireball is drawn from that reanimation, "
                            + "without it the pea is invisible and only its shadow is left on the lawn");
        } else {
            System.out.println("[content-mod-e2e] no retail assets on this machine: the fire-pea check "
                    + "was skipped (the fire path reads the reanimation's pea-head offset)");
        }

        // 5b) The mid-level save the user reported about (../verification/VERIFICATION.md §50): the plant comes
        //     back with its red art rebuilt - the save carries the reanimations but not the mod's
        //     per-track image overrides, so the API's onRestored dispatch is what repaints it - and it
        //     still fires its fire pea. The load must also not heal the injured plant.
        assertEquals("true", values.get("reloadSavedPlantPlaced"));
        assertEquals("true", values.get("reloadSaved"), "the mid-level save must be written");
        assertEquals("true", values.get("reloadLoaded"), "a fresh board must load that save");
        assertEquals("true", values.get("reloadPlantFound"),
                "the registered plant must survive the save/load round trip");
        assertEquals("111", values.get("reloadHealth"),
                "a load must not re-apply the registration's health over an injured plant");
        if (assetsPresent) {
            assertEquals("13", values.get("reloadTracksRepainted"),
                    "the restored plant's body tracks must be repainted, got "
                            + values.get("reloadTracksRepainted"));
            assertEquals("13", values.get("reloadHeadRepainted"),
                    "the restored plant's head tracks must be repainted, got "
                            + values.get("reloadHeadRepainted"));
            assertEquals("true", values.get("reloadZombieSpawned"));
            assertEquals(String.valueOf(ProjectileType.PROJECTILE_FIREBALL), values.get("reloadFireProjectileTypes"),
                    "the restored plant must still fire the fire pea");
        } else {
            assertEquals("0", values.get("reloadTracksRepainted"),
                    "without retail .reanim data there is no track image to repaint");
            assertEquals("0", values.get("reloadHeadRepainted"));
            System.out.println("[content-mod-e2e] no retail assets on this machine: the restored-art "
                    + "check was skipped");
        }

        // 6) The two plants added next to the Red Peashooter: their unlock gates, their multi-shot
        //    volleys and their red art.
        assertEquals("true", values.get("redRepeaterLockedBefore"),
                "a first-time player has neither the Repeater's award nor the red twin");
        assertEquals("true", values.get("redGatlingLockedBefore"));
        assertEquals("true", values.get("repeaterAvailableAfterAdventure"));
        assertEquals("true", values.get("redRepeaterUnlockedAfterAdventure"),
                "obtaining the Repeater must unlock the red twin");
        assertEquals("true", values.get("redGatlingStillLocked"),
                "the adventure's award must not unlock the shop-gated twin");
        assertEquals("true", values.get("gatlingAvailableAfterPurchase"));
        assertEquals("true", values.get("redGatlingUnlockedAfterPurchase"),
                "buying the shop's Gatling Pea must unlock the red twin");

        if (assetsPresent) {
            assertEquals("true", values.get("redPeashooterVolleyPlaced"));
            assertEquals("1", values.get("redPeashooterVolleyProjectiles"),
                    "the peashooter twin fires one pea per volley");
            assertEquals("true", values.get("redRepeaterVolleyPlaced"));
            assertEquals("true", values.get("redRepeaterVolleyTargetFound"));
            assertEquals("2", values.get("redRepeaterVolleyProjectiles"),
                    "the repeater twin fires two peas per volley, got "
                            + values.get("redRepeaterVolleyProjectiles"));
            assertEquals("true", values.get("redGatlingVolleyPlaced"));
            assertEquals("true", values.get("redGatlingVolleyTargetFound"));
            assertEquals("4", values.get("redGatlingVolleyProjectiles"),
                    "the gatling twin fires four peas per volley, got "
                            + values.get("redGatlingVolleyProjectiles"));
            assertEquals(fireballList(1), values.get("redPeashooterVolleyProjectileTypes"));
            assertEquals(fireballList(2), values.get("redRepeaterVolleyProjectileTypes"));
            assertEquals(fireballList(4), values.get("redGatlingVolleyProjectileTypes"));

            // The red art of the two new plants: their retail reanimation tracks were recoloured and
            // the game's cached frame for each registered seed type is the red one.
            assertEquals("true", values.get("redRepeaterPlaced"));
            assertEquals("true", values.get("redGatlingPlaced"));
            assertEquals("true", values.get("redRepeaterHeadReanim"),
                    "the mod must give the repeater twin the head reanimation too");
            assertEquals("true", values.get("redGatlingHeadReanim"));
            assertEquals("true", values.get("redRepeaterHeadleafOverride"),
                    "the repeater's headleaf tracks must be recoloured");
            assertEquals("true", values.get("redRepeaterHeadleafRed"),
                    "the recoloured headleaf must be red");
            assertEquals("true", values.get("redGatlingBarrelOverride"),
                    "the gatling pea's barrel tracks must be recoloured");
            assertEquals("true", values.get("redGatlingFaceRed"),
                    "the recoloured head must be red");
            assertTrue(Integer.parseInt(values.get("redRepeaterBodyTracks")) > 0);
            assertTrue(Integer.parseInt(values.get("redRepeaterHeadTracks")) > 0);
            assertTrue(Integer.parseInt(values.get("redGatlingBodyTracks")) > 0);
            assertTrue(Integer.parseInt(values.get("redGatlingHeadTracks")) > 0);
            assertEquals("true", values.get("redRepeaterCachedFrameSet"));
            assertEquals("true", values.get("redRepeaterCachedFrameIsRed"));
            assertEquals("true", values.get("redGatlingCachedFrameSet"));
            assertEquals("true", values.get("redGatlingCachedFrameIsRed"));

            // The icons are each twin's own packet art, pixel for pixel, recoloured.
            assertEquals(values.get("redRepeaterRetailOpaquePixels"), values.get("redRepeaterPacketOpaquePixels"),
                    "the repeater twin's icon must paint exactly the retail repeater's packet pixels");
            assertEquals("100", values.get("redRepeaterPacketMaskAgreementPercent"));
            assertEquals(values.get("redGatlingRetailOpaquePixels"), values.get("redGatlingPacketOpaquePixels"),
                    "the gatling twin's icon must paint exactly the retail gatling pea's packet pixels");
            assertEquals("100", values.get("redGatlingPacketMaskAgreementPercent"));
            assertTrue(Integer.parseInt(values.get("redRepeaterPacketRecolouredPixels")) > 200,
                    "the repeater twin's icon must be recoloured");
            assertTrue(Integer.parseInt(values.get("redGatlingPacketRecolouredPixels")) > 200,
                    "the gatling twin's icon must be recoloured");
        } else {
            System.out.println("[content-mod-e2e] no retail assets on this machine: the volley and red-art "
                    + "checks for the two new plants were skipped");
        }

        // 7) Planting an upgrade (../verification/VERIFICATION.md §54): the mod's red gatling pea is the red
        //    repeater's upgrade, exactly like the shop's gatling pea is the retail repeater's - and it
        //    upgrades the retail repeater too, since it is that plant's red twin. The retail pair runs
        //    first as the control: the same game path with the same two questions, so a harness that
        //    faked the planting instead of driving Board::MouseDown would fail there as well.
        assertEquals("true", values.get("retailGatlingOnRetailRepeaterPlaced"));
        assertEquals("true", values.get("retailGatlingOnRetailRepeaterUpgradable"),
                "the shop's gatling pea upgrades a retail repeater - the game's own hard-coded pair");
        assertEquals("PLANTING_OK", values.get("retailGatlingOnRetailRepeaterCanPlant"));
        assertEquals("true", values.get("retailGatlingReplacesRetailRepeater"),
                "the retail pair must plant through the game's own path, replacing the repeater");

        assertEquals("true", values.get("redGatlingOnRedRepeaterPlaced"));
        assertEquals("true", values.get("redGatlingOnRedRepeaterUpgradable"),
                "the red gatling pea must upgrade the red repeater - the user's report");
        assertEquals("PLANTING_OK", values.get("redGatlingOnRedRepeaterCanPlant"),
                "the red repeater's square must take the red gatling pea");
        assertEquals(values.get("redGatlingSeedType"),
                values.get("redGatlingReplacesRedRepeaterSeedType"),
                "the square must hold the red gatling pea the mod registered");
        assertEquals("true", values.get("redGatlingReplacesRedRepeater"),
                "planting the red gatling pea must replace the red repeater with it");
        assertEquals("true", values.get("redGatlingReplacesRedRepeaterOriginalDead"),
                "the replaced red repeater must be gone");

        assertEquals("true", values.get("redGatlingOnRetailRepeaterPlaced"));
        assertEquals("true", values.get("redGatlingOnRetailRepeaterUpgradable"),
                "the red gatling pea is the retail repeater's red twin, so it upgrades it too");
        assertEquals("PLANTING_OK", values.get("redGatlingOnRetailRepeaterCanPlant"));
        assertEquals("true", values.get("redGatlingReplacesRetailRepeater"));

        assertEquals("PLANTING_NEEDS_UPGRADE", values.get("redGatlingOnEmptySquareCanPlant"),
                "an upgrade seed needs the plant it upgrades");
        assertEquals("PLANTING_NEEDS_UPGRADE", values.get("retailGatlingOnEmptySquareCanPlant"),
                "the retail gatling pea's own behaviour on an empty square");
        assertEquals("true", values.get("redGatlingOnEmptySquareStaysEmpty"),
                "the game must refuse to plant an upgrade seed on an empty square");

        // 8) The game is still running the level, and the harness saw no exception at all.
        assertEquals("PLAYING", values.get("scene"));
        assertEquals("none", values.get("harnessError"));
    }

    /** @return {@code theCount} fire-pea projectile-type values, comma separated, as the harness reports */
    private static String fireballList(int theCount) {
        return String.join(",", java.util.Collections.nCopies(theCount,
                String.valueOf(ProjectileType.PROJECTILE_FIREBALL)));
    }

    /**
     * Asserts that the packet of a registered plant paints about as many pixels as its retail twin's
     * own packet. The mod's icon is the twin's packet art re-mapped and it is drawn 1:1, so the same
     * mask comes out; the tolerance is what an upgrade packet's own background could add.
     */
    private static void assertPacketOpaqueNear(Map<String, String> values, String theRed, String theRetail) {
        int aRed = Integer.parseInt(values.get(theRed + "PacketOpaquePixels"));
        int aRetail = Integer.parseInt(values.get(theRetail + "PacketOpaquePixels"));
        double aDifference = Math.abs(aRed - aRetail) / (double) Math.max(aRetail, 1);
        assertTrue(aDifference <= 0.05,
                theRed + "'s packet must paint the pixels " + theRetail + "'s does, got " + aRed + " vs "
                        + aRetail);
    }

    /** The jar a player drops into {@code mods/} must carry the metadata and the code, and no mechanism. */
    private void assertModJarContents(Path modJar) throws IOException {
        Set<String> entries;
        try (JarFile jar = new JarFile(modJar.toFile())) {
            entries = jar.stream().map(JarEntry::getName).collect(Collectors.toSet());
        }

        assertTrue(entries.contains("fabric.mod.json"), "the jar must carry fabric.mod.json, got " + entries);
        assertTrue(entries.contains("cn/enaium/fabricpvz/contentmod/ContentMod.class"), "the entry point class");
        assertTrue(entries.contains("cn/enaium/fabricpvz/contentmod/RedPlantsArt.class"), "the art choice");
        assertTrue(entries.stream().noneMatch(name -> name.endsWith(".mixins.json")),
                "the mod ships no Mixin any more: the api-resource module owns that mechanism, got " + entries);
        assertTrue(entries.stream().noneMatch(name -> name.startsWith("cn/enaium/fabricpvz/contentmod/mixin/")),
                "the mod carries no Mixin class any more, got " + entries);

        // The art is not shipped as a file: the packet icon is the game's own peashooter packet art
        // rendered and re-mapped at runtime, so the jar carries no textures at all. That is what keeps
        // the icon from drifting from the plant (a hand-rendered PNG would, see
        // {@code ../verification/VERIFICATION.md} §46).
        assertTrue(entries.stream().noneMatch(name -> name.startsWith("assets/")),
                "the mod ships no art files, got " + entries);
    }

    /**
     * Starts the real Fabric Loader in a second JVM, exactly like the launcher would.
     *
     * @return the launcher's exit code
     */
    private int launch(Path mods, Path runDir, Path report, Path output) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("--enable-native-access=ALL-UNNAMED");

        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")) {
            // SDL (LWJGL's bindings) needs the first thread.
            command.add("-XstartOnFirstThread");
        }

        command.add("-cp");
        command.add(forkClasspath());
        // The harness entry point lives in this project's test classes, which are not part of the
        // launcher's class path proper: the provider adds these entries to the loader's own class
        // loader, so the harness and the mod/API/game classes it inspects are all defined by the same
        // class loader (otherwise it would read a second copy of every static).
        command.add("-Dpvz.extraClasspath=" + harnessDirectory());
        // Development mode: the loader also discovers the mods that are on the class path (the API
        // modules resolved from mavenLocal). The mod under test is deliberately NOT there: it is only
        // in the mods directory, exactly like a jar a player copied into the installation.
        command.add("-Dfabric.development=true");
        // This port is the only provider on the class path.
        command.add("-Dfabric.skipMcProvider=true");
        // A crash must exit instead of opening the loader's Swing error window.
        command.add("-Dfabric.noGui=true");
        command.add("-Dpvz.gameDir=" + runDir);
        command.add("-Dfabric.modsFolder=" + mods);
        command.add("-Dpvz.game.mainClass=cn.enaium.fabricpvz.contentmod.test.ContentModGameMain");
        command.add("-Dpvz.test.report=" + report);
        String assets = resourceRoot();
        if (!assets.isBlank()) {
            command.add("-Dpvz.test.resources=" + assets);
        }
        command.add(LAUNCHER_CLASS);
        command.add("-savedir");
        command.add(runDir.resolve("userdata").toString());

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(tmp.toFile())
                .redirectOutput(output.toFile())
                .redirectErrorStream(true);
        System.out.println("[content-mod-e2e] boot command:\n  " + String.join(" \\\n  ", command));
        Process process = builder.start();

        if (!process.waitFor(15, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("Fabric Loader did not finish; launcher output:\n" + read(output));
        }

        System.out.println("[content-mod-e2e] " + LAUNCHER_CLASS + " exited with " + process.exitValue());
        System.out.println("[content-mod-e2e] launcher output:\n" + read(output));
        return process.exitValue();
    }

    /**
     * The class path the launcher runs with: the test classes, the loader, the API modules, the game
     * and the game provider - but not this project's own main output, so the mod can only come from
     * the mods directory.
     */
    private static String forkClasspath() {
        String classpath = System.getProperty(FORK_CLASSPATH_PROPERTY, "");
        if (classpath.isBlank()) {
            fail("-D" + FORK_CLASSPATH_PROPERTY + " is not set (run through Gradle)");
        }
        return classpath;
    }

    /**
     * The directory holding the harness classes (this test's own output directory).
     */
    private static String harnessDirectory() {
        try {
            return Path.of(ContentModEndToEndTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString();
        } catch (Exception e) {
            throw new IllegalStateException("cannot locate the harness classes", e);
        }
    }

    /**
     * The retail resource root this machine has installed, or an empty string. Discovered with the
     * game's own locator (the Steam library search), so the run uses the real assets when they are
     * there and still verifies the registrations when they are not.
     */
    private static String resourceRoot() {
        String override = System.getProperty("pvz.test.resources", "");
        if (!override.isBlank()) {
            return override;
        }

        Path discovered = cn.enaium.fabricpvz.portable.pvzp.ResourceLocator.discover(new String[0]).resourceRoot();
        return discovered == null ? "" : discovered.toString();
    }

    private static Map<String, String> readReport(Path report) throws IOException {
        assertTrue(Files.isRegularFile(report), "the harness must have written its report");
        return parse(Files.readString(report));
    }

    private static Map<String, String> parse(String content) {
        Map<String, String> values = new HashMap<>();
        for (String line : content.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                values.put(line.substring(0, equals), line.substring(equals + 1).trim());
            }
        }
        return values;
    }

    private static String read(Path path) throws IOException {
        return Files.exists(path) ? Files.readString(path) : "<no output>";
    }

    private static String failureMessage(Path output, Path report) throws IOException {
        String tail = read(output);
        int keep = 4000;
        if (tail.length() > keep) {
            tail = "..." + tail.substring(tail.length() - keep);
        }
        return "\n--- launcher output (tail) ---\n" + tail
                + "\n--- report ---\n" + (Files.exists(report) ? Files.readString(report) : "<none>");
    }

    private static String requiredProperty(String theName) {
        String value = System.getProperty(theName, "");
        assertNotNull(value, "-D" + theName + " is required");
        assertTrue(!value.isBlank(), "-D" + theName + " is required");
        return value;
    }
}
